/*
    Parasite, version [unreleased]. Copyright 2023 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
    file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
    either express or implied. See the License for the specific language governing permissions
    and limitations under the License.
*/

package parasite

import anticipation.*
import rudiments.*
import digression.*
import perforate.*

import scala.annotation.*
import scala.collection.mutable as scm

import java.util.concurrent.atomic as juca

import language.experimental.captureChecking

object Promise:
  object Cancelled
  object Incomplete

case class Promise[ValueType]():
  private var value: ValueType | Promise.Cancelled.type | Promise.Incomplete.type = Promise.Incomplete

  inline def cancelled: Boolean = value == Promise.Cancelled
  inline def incomplete: Boolean = value == Promise.Incomplete
  inline def ready: Boolean = !incomplete

  private def get()(using Raises[CancelError]): ValueType =
    if cancelled then abort(CancelError()) else value.asInstanceOf[ValueType]

  def fulfill(supplied: -> ValueType)(using complete: Raises[AlreadyCompleteError]): Unit^{complete} =
    synchronized:
      if !incomplete then raise(AlreadyCompleteError())(()) else value = supplied
      notifyAll()
  
  def offer(supplied: -> ValueType): Unit =
    synchronized:
      if incomplete then
        value = supplied
        notifyAll()

  def await()(using Raises[CancelError]): ValueType = synchronized:
    while !ready do wait()
    get()

  def cancel(): Unit = synchronized:
    value = Promise.Cancelled
    notifyAll()

  def await
      [DurationType: GenericDuration]
      (duration: DurationType)(using Raises[CancelError], Raises[TimeoutError])
      : ValueType =
    
    synchronized:
      if ready then get() else
        wait(duration.milliseconds)
        if !ready then abort(TimeoutError()) else get()

case class Trigger():
  private val promise: Promise[Unit] = Promise()
  def apply(): Unit = promise.offer(())
  def pull()(using Raises[AlreadyCompleteError]): Unit = promise.fulfill(())
  def await()(using Raises[CancelError]): Unit = promise.await()
  def cancel(): Unit = promise.cancel()
  def cancelled: Boolean = promise.cancelled
  
  def await[DurationType: GenericDuration](duration: DurationType)(using Raises[CancelError], Raises[TimeoutError]): Unit =
    promise.await(duration)

@capability
sealed trait Monitor(val name: List[Text], trigger: Trigger):
  private val children: scm.HashMap[Text, AnyRef] = scm.HashMap()
  def id: Text = Text(name.reverse.map(_.s).mkString(" / "))

  def cancel(): Unit =
    children.foreach: (id, child) =>
      child match
        case child: Monitor => child.cancel()
        case _              => ()

    trigger.cancel()

  def terminate(): Unit = this match
    case Supervisor                                     => Supervisor.cancel()
    case monitor@Submonitor(id, parent, state, promise) => monitor.terminate()

  def sleep(duration: Long): Unit = Thread.sleep(duration)

  def child
      [ResultType2]
      (id: Text, state: juca.AtomicReference[AsyncState[ResultType2]], trigger: Trigger)
      (using label: boundary.Label[Unit])
      : Submonitor[ResultType2] =
    
    val monitor = Submonitor[ResultType2](id, this, state, trigger)
    
    synchronized:
      children(id) = monitor
    
    monitor

case object Supervisor extends Monitor(Nil, Trigger())

def supervise
    [ResultType]
    (fn: Monitor ?=> ResultType)(using cancel: Raises[CancelError])
    : ResultType =
  fn(using Supervisor)

@capability
case class Submonitor
    [ResultType]
    (identifier: Text, parent: Monitor, stateRef: juca.AtomicReference[AsyncState[ResultType]], trigger: Trigger)
    (using label: boundary.Label[Unit])
extends Monitor(identifier :: parent.name, trigger):

  def state(): AsyncState[ResultType] = stateRef.get().nn
  
  def complete(value: ResultType): Nothing =
    stateRef.set(Completed(value))
    trigger()
    boundary.break()
  
  def acquiesce(): Unit = synchronized:
    stateRef.get().nn match
      case Active            => ()
      case Suspended(_)      => wait()
      case Completed(value)  => trigger()
      case Cancelled         => trigger()
      case Failed(error)     => trigger()
    
    if trigger.cancelled then boundary.break()
  
