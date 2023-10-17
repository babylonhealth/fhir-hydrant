package com.emed.hydrant.profilegen

import com.emed.hydrant.{
  ElementType,
  FhirTypeProvider,
  Snapshot,
  StructureDefinitionTypeProvider,
  ElementDefinition as HydrantElementDefinition,
  StructureDefinition as HydrantStrucutreDefinition
}
import com.babylonhealth.lit.core.ChoiceImplicits.*
import com.babylonhealth.lit.core.*
import com.babylonhealth.lit.core.model.{ CodeableConcept, Coding, Meta, resourceTypeLookup }
import com.babylonhealth.lit.core.serdes.{ objectDecoder, objectEncoder }
import com.babylonhealth.lit.hl7.*
import com.babylonhealth.lit.hl7.model.ElementDefinition.Binding
import com.babylonhealth.lit.hl7.model.ElementDefinition.Slicing.Discriminator
import com.babylonhealth.lit.hl7.model.{ DomainResource, ElementDefinition, StructureDefinition }
import io.circe.parser.parse
import io.github.classgraph.ClassGraph

import scala.jdk.CollectionConverters.*
import java.io.File
import java.nio.file.{ Files, Path }

/** Used for handling hl7 FHIR resource structure definitions and any additional FHIR profiles provided. */
class StructureDefinitions(hl7Defns: Seq[StructureDefinition], extraDefns: Seq[StructureDefinition] = Seq.empty)
    extends StructureDefinitionTypeProvider((hl7Defns ++ extraDefns) map StructureDefinitions.defnFromLit)
    with ChoiceFields {
  val hl7DefnByType: Map[String, StructureDefinition]        = hl7Defns.groupMapReduce(_.`type`: String)(identity)((a, _) => a)
  lazy val extraDefnsByUrl: Map[UriStr, StructureDefinition] = extraDefns.groupMapReduce(_.url)(identity)((a, _) => a)
}

object StructureDefinitions {
  def defnFromLit(d: StructureDefinition): HydrantStrucutreDefinition = HydrantStrucutreDefinition(snapshot = d.snapshot.map(s =>
    Snapshot(s.element.map(e => HydrantElementDefinition(e.path, Some(e.`type`.map(t => ElementType(t.code)).toList), e.max)).toList)))

  private def parse(str: String) = io.circe.parser.decode[StructureDefinition](str)

  def apply(hl7Defns: Seq[StructureDefinition], extraDefns: Seq[StructureDefinition] = Seq.empty) =
    new StructureDefinitions(hl7Defns, extraDefns)

  def apply(folder: File): StructureDefinitions =
    apply(folder.list().toList.map(p => parse(Files.readString(Path.of(p))).toTry.get))

  def fromClassPath(packageName: String = "profiles"): StructureDefinitions = {
    val extensions = Vector("json", "yml", "yaml")
    val scan       = new ClassGraph().acceptPackages(packageName).scan
    val structureDefinitions =
      extensions.flatMap(
        scan
          .getResourcesWithExtension(_)
          .asScala
          .map(_.getContentAsString)
          .map(parse(_).toTry.get))

    apply(structureDefinitions)
  }

  given StructureDefinitions = fromClassPath()
}
