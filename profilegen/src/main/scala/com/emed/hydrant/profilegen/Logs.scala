package com.emed.hydrant.profilegen

/** Simple logging trait */
trait Logs {
  def log(msg: String): Unit
  def apply(msg: String): Unit = log(msg)
}

object PrintLogs extends Logs {
  override def log(msg: String): Unit = println(msg)
}

object Logs {
  given Logs = PrintLogs
}
