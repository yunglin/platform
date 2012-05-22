/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package actor

import com.precog.common._

import akka.actor.Actor
import akka.actor.Scheduler
import akka.actor.ActorRef
import akka.dispatch.Await
import akka.dispatch.Future
import akka.dispatch.ExecutionContext
import akka.pattern.ask
import akka.util.Timeout
import akka.util.duration._
import akka.util.Duration

import blueeyes.json.JsonAST._

import com.weiglewilczek.slf4s._

import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec
import scala.collection.mutable
import scalaz._

case class InsertComplete(eventId: EventId, descriptor: ProjectionDescriptor, values: Seq[CValue], metadata: Seq[Set[Metadata]])
case class InsertBatchComplete(inserts: Seq[InsertComplete])

class RoutingDispatch(routingTable: RoutingTable, projectionActors: ActorRef, metadataActor: ActorRef, batchTimeout: Duration)(implicit timeout: Timeout, execContext: ExecutionContext) extends Logging {
  type ActionMap = mutable.Map[ProjectionDescriptor, (Seq[ProjectionInsert], Seq[InsertComplete])]

  val batchCounter = new AtomicLong(0)

  def storeAll(events: Iterable[IngestMessage]): Future[Validation[Throwable, Unit]] = {
    import scala.collection.mutable

    var actionMap = buildActions(events)
    dispatchActions(actionMap)
  }

  def buildActions(events: Iterable[IngestMessage]): ActionMap = {
    val actions = mutable.Map.empty[ProjectionDescriptor, (Seq[ProjectionInsert], Seq[InsertComplete])]

    @inline @tailrec
    def update(eventId: EventId, updates: Iterator[ProjectionData]): Unit = {
      if (updates.hasNext) {
        val ProjectionData(descriptor, identities, values, metadata) = updates.next()
        val insert = ProjectionInsert(identities, values)
        val complete = InsertComplete(eventId, descriptor, values, metadata)

        val (inserts, completes) = actions.getOrElse(descriptor, (Vector.empty, Vector.empty))
        val newActions = (inserts :+ insert, completes :+ complete) 
        
        actions += (descriptor -> newActions)
        update(eventId, updates)
      } 
    }

    @inline @tailrec
    def build(events: Iterator[IngestMessage]): Unit = {
      if (events.hasNext) {
        events.next() match {
          case em @ EventMessage(eventId, _) => update(eventId, routingTable.route(em).iterator)
          case _ => ()
        }

        build(events)
      }
    }

    build(events.iterator)
    actions
  }

  def dispatchActions(actions: ActionMap): Future[Validation[Throwable, Unit]] = {
    (projectionActors ? AcquireProjectionBatch(actions.keys)) flatMap {
      case ProjectionBatchAcquired(actorMap) =>
        for {
          _     <-  Future(batchCounter.incrementAndGet)

          descs <-  Future.sequence {
                      actions map {
                        case (desc, (inserts, completes)) =>
                          for {
                            _ <- actorMap(desc) ? ProjectionBatchInsert(inserts)
                            _ <- metadataActor  ? UpdateMetadata(completes)
                          } yield desc
                      }
                    }

          _     <-  projectionActors ? ReleaseProjectionBatch(descs.toSeq)
        } yield {
          batchCounter.decrementAndGet 
          Success(()) 
        }

      case ProjectionError(ex) =>
        Future(Failure(ex))
    }
  }
}
// vim: set ts=4 sw=4 et: