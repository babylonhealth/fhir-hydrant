package com.emed.hydrant.profilegen

import com.babylonhealth.lit.hl7.model.DomainResource

import java.nio.file.Path
import io.circe.syntax._
import com.babylonhealth.lit.core.serdes.{ objectDecoder, objectEncoder }
import java.io.FileWriter

trait OutputFile {
  val path: Path
  def body: String
}

case class ConformanceResourceFile(path: Path, rsc: DomainResource) extends OutputFile {
  override def body = rsc.asJson.spaces2
  def write() = {
    val fw = new FileWriter(path.toFile)
    try fw.write(body)
    finally fw.close()
  }
}
