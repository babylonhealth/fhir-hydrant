package com.emed.hydrant

import io.circe.{ ACursor, Json, JsonObject }

sealed trait HydrateInfo {
  def definition: HydrationDefinition
  def dehydrated: JsonObject
  def isContained: Boolean
  def path: Seq[String]
  def fhirPath: String
  def print: String = s"(${(definition.id +: path).mkString(".")})"
}

case class RootInfo(definition: HydrationDefinition, dehydrated: JsonObject) extends HydrateInfo {
  override def isContained       = false
  override def path: Seq[String] = Seq.empty
  override def fhirPath: String  = definition.resourceType.getOrElse("")
}

case class ChildInfo(
    paramName: String,
    param: ParamInfo,
    definition: HydrationDefinition,
    dehydrated: JsonObject,
    parent: HydrateInfo,
    index: Option[Int] = None)
    extends HydrateInfo {
  override def isContained: Boolean = param.isContained
  override def path: Seq[String]    = (parent.path :+ paramName) ++ index.map(_.toString)
  override def fhirPath: String     = f"${parent.fhirPath}.$paramName"
}
