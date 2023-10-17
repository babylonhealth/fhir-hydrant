package com.emed.hydrant

import io.circe.Json
import io.circe.parser.*

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.Charset
import scala.annotation.tailrec
import scala.collection.{ IterableOps, View }
import PathMethods.*
import com.emed.hydrant.Definitions.decode

import scala.jdk.CollectionConverters.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import io.github.classgraph.ClassGraph

import scala.util.Try

/** Provides information from HL7 StructureDefinitions */
trait FhirTypeProvider {

  /** Given a path e.g. "Observation.code" return the list of all types as strings e.g. Set("CodeableConcept"). */
  def typesOf(path: String): Set[String]

  /** Given a path e.g. "Observation.code" return the maximum cardinality at that path in FHIR notation e.g. "1" or "*". */
  def maxOf(path: String): Option[String]
}

object FhirTypeProvider {
  given FhirTypeProvider = StructureDefinitionTypeProvider.fromClassPath()
}

/** FhirTypeProvider from a sequence of StructureDefinitions. */
class StructureDefinitionTypeProvider(structureDefinitions: Seq[StructureDefinition]) extends FhirTypeProvider {

  val elementDefinitionByPath: Map[String, ElementDefinition] = {
    for {
      structureDefinition <- structureDefinitions
      elementDefinition   <- structureDefinition.snapshot.map(_.element).getOrElse(Nil)
    } yield elementDefinition.path -> elementDefinition
  }.toMap

  private val typeByPath: Map[String, Set[String]] =
    elementDefinitionByPath.view
      .mapValues(_.`type`.map(_.map(_.code).toSet).getOrElse(Set.empty))
      .toMap
      .flatMap {
        case (path, types) if path.endsWith("[x]") =>
          types.map(t => (path.dropRight(3) + t.capitalize, Set(t))) + (path -> types)
        case pathTypes => List(pathTypes)
      } + ("Extension.url" -> Set("uri")) // For some reason it thinks Extension.url is a string

  override def typesOf(path: String): Set[String]  = typesOf(path, "")
  override def maxOf(path: String): Option[String] = elementDefinitionByPath.get(path).flatMap(_.max)

  // Will return Set() if sliced, only call on genuine path, not id
  @tailrec
  private def typesOf(path: String, downPath: String): Set[String] = {
    typeByPath.get(path) match {
      case Some(ts) if ts.isEmpty && downPath.isEmpty && (path.count(_ == '.') == 0) => Set(path)
      case Some(ts) if downPath.isEmpty                                              => ts
      case Some(ts)                                                                  => ts.flatMap(t => typesOf(t + "." + downPath))
      case None if downPath.isEmpty && path.isEmpty                                  => Set()
      case None if downPath.isEmpty                                                  => typesOf(path.dropLastToken, path.lastToken)
      case None if path.count(_ == '.') == 0                                         => Set()
      case None => typesOf(path.dropLastToken, path.lastToken + "." + downPath)
    }
  }
}

object StructureDefinitionTypeProvider {

  def apply(structureDefinitions: Seq[StructureDefinition]): StructureDefinitionTypeProvider =
    new StructureDefinitionTypeProvider(structureDefinitions)

  def apply(folder: File): StructureDefinitionTypeProvider =
    apply(folder.list().toList.map(p => StructureDefinition.parse(Files.readString(Path.of(p))).toTry.get))

  def fromClassPath(packageName: String = "profiles"): StructureDefinitionTypeProvider = {
    val extensions = Vector("json", "yml", "yaml")
    val scan       = new ClassGraph().acceptPackages(packageName).scan
    val structureDefinitions =
      extensions.flatMap(
        scan
          .getResourcesWithExtension(_)
          .asScala
          .map(_.getContentAsString)
          .map(StructureDefinition.parse(_).toTry.get))

    apply(structureDefinitions)
  }
}
