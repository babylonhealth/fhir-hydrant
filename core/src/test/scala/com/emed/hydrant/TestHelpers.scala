package com.emed.hydrant

import io.circe.parser
import io.circe.syntax.*
import io.github.classgraph.{ClassGraph, Resource}
import org.scalactic.Prettifier

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, OffsetDateTime, ZoneOffset}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Try
import cats.implicits.*
import io.circe.parser.parse

import java.io.FileWriter
import scala.io.BufferedSource
import scala.util.matching.Regex

object TestHelpers {

  def parse(str: String)    = parser.parse(str).fold(throw _, identity)
  def parseObj(str: String) = parse(str).asObject.get

  lazy val templates: Definitions = Definitions.fromClassPath()

  val redactObjects = Set(TemplateJson.Obj("system" -> TemplateString.literal("https://bbl.health")))
  
  def template(id: String)      = templates(id).toTry.get.asInstanceOf[Template]
  def enumDef(id: String)       = templates(id).toTry.get.asInstanceOf[EnumDefinition]
  def childTemplate(id: String) = templates(id).toTry.get.asInstanceOf[ChildTemplate]

  def rscStream(s: String): BufferedSource = scala.io.Source.fromInputStream(getClass.getResourceAsStream(s))

  def slurpRsc(s: String): String = rscStream(s"/$s").getLines.mkString("\n")

  def spurt(location: String, contents: String): Unit = {
    val fw = new FileWriter(location)
    try fw.write(contents)
    finally fw.close()
  }
}
