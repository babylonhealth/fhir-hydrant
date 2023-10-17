package com.emed.hydrant

import io.circe.Error
import io.circe.syntax.*
import io.circe.generic.auto.*

case class Coding(system: Option[String], code: Option[String], display: Option[String])

/** Minimal models for getting types from FHIR structure definition snapshots * */
case class ElementType(code: String)
case class ElementDefinition(path: String, `type`: Option[List[ElementType]], max: Option[String])
case class Snapshot(element: List[ElementDefinition])
case class StructureDefinition(snapshot: Option[Snapshot])
object StructureDefinition {
  def parse(str: String): Either[Error, StructureDefinition] = io.circe.parser.decode[StructureDefinition](str)
}
