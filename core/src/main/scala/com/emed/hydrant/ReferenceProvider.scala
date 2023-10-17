package com.emed.hydrant

import io.circe.Json
import cats.implicits.*

import scala.util.matching.Regex

/** Determines how references should be formatted */
trait ReferenceProvider {

  /** Given the Json representation of a FHIR resource, how should it be referenced */
  def referenceUriFromResource(resource: Json): Option[String]

  /** If not None, automatically add an identifier with the given system to resources */
  def identifierSystem(template: Template): Option[String] = None

  /** Only needed for profile generation. Override if you have a different implementation for reference uris. * */
  def resourceTypeFromReferenceUri(referenceUri: String): Option[String] = {
    // Standard references look like http://example.com/ResourceType/id - we extract ResourceType here
    val split = referenceUri.split('/')
    Option.when(split.length > 1)(split(split.length - 2))
  }

  /** Only needed for profile generation. Override to add additional profiles to references. * */
  def profilesFromReferenceUri(referenceUri: String): Seq[String] =
    resourceTypeFromReferenceUri(referenceUri).map(resourceType => s"http://hl7.org/fhir/StructureDefinition/$resourceType").toSeq
}

object ReferenceProvider {
  def apply(uriFromResource: Json => Option[String]): ReferenceProvider = uriFromResource(_)
  given ReferenceProvider                                               = IdentifierBasedReferenceProvider.default
}

/** ReferenceProvider from a way of making identifiers and a way of telling if a string is a valid identifier.
  * @param makeIdentifier
  *   A function (domain, resourceType) => identifier that constructs a reference
  * @param identifierSystemIsValid
  *   A function to find the first matching valid identifier of a resource, to use it as the system for a reference
  */
class IdentifierBasedReferenceProvider(makeIdentifier: (String, String) => String, identifierSystemIsValid: String => Boolean)
    extends ReferenceProvider {
  override def referenceUriFromResource(resource: Json): Option[String] = {

    def identifierToReference(json: Json): Option[String] =
      for {
        o      <- json.asObject
        s      <- o("system")
        system <- s.asString
        _ = println(system)
        _ = println(identifierSystemIsValid(system))
        if identifierSystemIsValid(system)
        v     <- o("value")
        value <- v.asString
      } yield s"$system/$value"

    for {
      obj             <- resource.asObject
      identifiersJson <- obj("identifier")
      identifiers = identifiersJson.asArray.getOrElse(Vector(identifiersJson))
      ref <- identifiers.collectFirstSome(identifierToReference)
    } yield ref
  }

  override def identifierSystem(template: Template): Option[String] =
    template.resourceType.map(resourceType => makeIdentifier(template.domain, resourceType))
}

object IdentifierBasedReferenceProvider {

  /** Given a pattern */
  def fromPattern(pattern: (String, String) => String) = {
    IdentifierBasedReferenceProvider(pattern, escape(pattern("🧯", "🧯")).replaceAll("🧯", "([A-Za-z0-9_-]+)").r.matches)
  }

  lazy val default: ReferenceProvider = ReferenceProvider(resource =>
    for {
      obj             <- resource.asObject
      resourceType    <- obj("resourceType")
      resourceTypeStr <- resourceType.asString
      id              <- obj("id")
      idStr           <- id.asString
    } yield s"$resourceTypeStr/$idStr")

  private def escape(str: String) =
    List("\\", ".", "+", "*", "?", "^", "$", "(", ")", "[", "]", "{", "}", "|").foldLeft(str)((s, c) => s.replace(c, s"\\$c"))
}
