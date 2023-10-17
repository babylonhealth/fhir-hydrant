package com.emed.hydrant

import io.circe.{ Json, JsonObject }

case class SecondaryResources private (private val references: Map[String, Json]) {
  def apply(id: String): Option[Json] = references.get(id)
}

object SecondaryResources {
  def apply(first: Json, rest: Iterable[Json]): SecondaryResources = {
    val contained = containedReferences(first)

    val ids = for {
      resource  <- rest
      obj       <- resource.asObject.toSeq
      reference <- absoluteReferences(obj) ++ relativeReferences(obj)
    } yield reference

    new SecondaryResources((contained ++ ids).toMap)
  }

  private def absoluteReferences(obj: JsonObject): Iterable[(String, Json)] = for {
    identifiersJson <- obj("identifier").toSeq
    identifierJson  <- identifiersJson.asArray.orElse(Some(Seq(identifiersJson))).toSeq.flatten
    identifier      <- identifierJson.asObject
    systemJson      <- identifier("system")
    system          <- systemJson.asString
    valueJson       <- identifier("value")
    value           <- valueJson.asString
  } yield s"$system/$value" -> Json.fromJsonObject(obj)

  private def relativeReferences(obj: JsonObject): Iterable[(String, Json)] = for {
    resourceTypeJson <- obj("resourceType")
    resourceType     <- resourceTypeJson.asString
    idJson           <- obj("id")
    id               <- idJson.asString
  } yield s"$resourceType/$id" -> Json.fromJsonObject(obj)

  private def containedReferences(resource: Json): Iterable[(String, Json)] = for {
    obj                    <- resource.asObject.toSeq
    containedResourcesJson <- obj("contained").toSeq
    containedJson          <- containedResourcesJson.asArray.toSeq.flatten
    contained              <- containedJson.asObject
    idJson                 <- contained("id")
    id                     <- idJson.asString
  } yield s"#$id" -> containedJson
}
