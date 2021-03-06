/*
 * Copyright (C) 2015 Red Bull Media House GmbH <http://www.redbullmediahouse.com> - all rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.rbmhtechnology.eventuate.log

import akka.event.LoggingAdapter

import com.rbmhtechnology.eventuate._
import com.rbmhtechnology.eventuate.EventsourcingProtocol.Write
import com.rbmhtechnology.eventuate.ReplicationProtocol.ReplicationWrite

import scala.collection.immutable.Seq

/**
 * Tracks the current sequence number and vector time of an event log. Besides maintaining the
 * current sequence number of an event log it also maintains the merged vector times of logged
 * events. The merged vector time is used to select those events for replication that are not
 * in the causal past of an event log.
 *
 * Please note that the time tracker is not a vector clock. Vector clocks are maintained by event
 * sourced actors directly (see also [[VectorClock]]).
 *
 * @param updateCount Number of updates made to the log.
 * @param sequenceNr Current sequence number of the log.
 * @param vectorTime Current vector time of the log. This is the merge
 *                   result of all event vector timestamps in the log.
 */
private[eventuate] case class TimeTracker(updateCount: Long = 0L, sequenceNr: Long = 0L, vectorTime: VectorTime = VectorTime.Zero) {
  def advanceUpdateCount(): Unit =
    copy(updateCount = updateCount + 1L)

  def advanceSequenceNr(delta: Long = 1L): TimeTracker =
    copy(sequenceNr = sequenceNr + delta)

  def update(event: DurableEvent): TimeTracker =
    copy(sequenceNr = event.localSequenceNr, vectorTime = vectorTime.merge(event.vectorTimestamp))

  def prepareWrites(logId: String, writes: Seq[Write], systemTimestamp: Long): (Seq[Write], TimeTracker) =
    writes.foldLeft((Vector.empty[Write], this)) {
      case ((writes2, tracker2), write) => tracker2.prepareWrite(logId, write.events, systemTimestamp) match {
        case (updated, tracker3) => (writes2 :+ write.copy(events = updated), tracker3)
      }
    }

  def prepareReplicates(logId: String, writes: Seq[ReplicationWrite], logger: LoggingAdapter): (Seq[ReplicationWrite], TimeTracker) = {
    writes.foldLeft((Vector.empty[ReplicationWrite], this)) {
      case ((writes2, tracker2), write) => tracker2.prepareReplicate(logId, write.events, write.replicationProgress, logger) match {
        case (updated, tracker3) => (writes2 :+ write.copy(events = updated), tracker3)
      }
    }
  }

  def prepareWrite(logId: String, events: Seq[DurableEvent], systemTimestamp: Long): (Seq[DurableEvent], TimeTracker) = {
    var snr = sequenceNr
    var upd = updateCount
    var lvt = vectorTime

    val updated = events.map { e =>
      snr += 1L
      upd += 1L

      val e2 = e.prepare(logId, snr, systemTimestamp)
      lvt = lvt.merge(e2.vectorTimestamp)
      e2
    }

    (updated, copy(updateCount = upd, sequenceNr = snr, vectorTime = lvt))
  }

  def prepareReplicate(logId: String, events: Seq[DurableEvent], replicationProgress: Long, logger: LoggingAdapter): (Seq[DurableEvent], TimeTracker) = {
    var snr = sequenceNr
    var upd = updateCount
    var lvt = vectorTime

    val updated = events.foldLeft(Vector.empty[DurableEvent]) {
      case (acc, e) if e.before(vectorTime) =>
        // Exclude events from writing that are in the causal past of this event log.
        // Excluding them at the target is needed for correctness. Events are also
        // filtered at sources (to reduce network bandwidth usage) but this is only
        // an optimization which cannot achieve 100% filtering coverage for certain
        // replication network topologies.
        acc
      case (acc, e) =>
        snr += 1L
        upd += 1L

        val e2 = e.prepare(logId, snr, e.systemTimestamp)
        lvt = lvt.merge(e2.vectorTimestamp)
        acc :+ e2
    }
    TimeTracker.logFilterStatistics(logId, "target", events, updated, logger)
    (updated, copy(updateCount = upd, sequenceNr = snr, vectorTime = lvt))
  }
}

private[eventuate] object TimeTracker {
  def logFilterStatistics(logId: String, location: String, before: Seq[DurableEvent], after: Seq[DurableEvent], logger: LoggingAdapter): Unit = {
    val bl = before.length
    val al = after.length
    if (al < bl) {
      val diff = bl - al
      val perc = diff * 100.0 / bl
      logger.info(f"[$logId] excluded $diff events ($perc%3.1f%% at $location)")
    }
  }
}