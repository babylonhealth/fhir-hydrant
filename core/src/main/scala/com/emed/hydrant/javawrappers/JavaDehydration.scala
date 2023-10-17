package com.emed.hydrant.javawrappers

import com.emed.hydrant.*
import com.emed.hydrant.{ DisambiguationStrategy, Strict }
import io.circe.parser

import scala.jdk.CollectionConverters.IterableHasAsScala

// So that dehydration can be used more easily from Java code
class JavaDehydration(
    defs: java.lang.Iterable[HydrationDefinition],
    fhirTypeProvider: FhirTypeProvider,
    disambiguationStrategy: DisambiguationStrategy,
    strictFullListDehydrate: Boolean,
    checkNonOptional: Boolean) {

  def this(defs: java.lang.Iterable[HydrationDefinition], fhirTypeProvider: FhirTypeProvider) =
    this(defs, fhirTypeProvider, Strict, true, true)

  private val dehydration =
    new Dehydration(Definitions(defs.asScala), disambiguationStrategy, strictFullListDehydrate, checkNonOptional)(using fhirTypeProvider)

  def dehydrate(template: Template)(fhir: String): String =
    dehydration.dehydrate(template)(parser.parse(fhir).toTry.get).toTry.get.spaces2
}
