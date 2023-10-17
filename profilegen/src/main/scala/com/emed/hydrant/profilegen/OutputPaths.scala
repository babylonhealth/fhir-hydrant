package com.emed.hydrant.profilegen

import java.nio.file.{ Path, Paths }

/** Defines the file paths to write output conformance resources to in profile generation */
trait OutputPaths {
  def valueSetOutputPath(valueSetId: String): Path
  def profileOutputPath(profileId: String): Path
  def extensionOutputPath(extensionId: String): Path
}

object DefaultOutputPaths extends OutputPaths {
  override def valueSetOutputPath(valueSetId: String): Path   = Paths.get(s"valueSets/$valueSetId.ValueSet.json")
  override def profileOutputPath(profileId: String): Path     = Paths.get(s"profiles/$profileId.StructureDefinition.json")
  override def extensionOutputPath(extensionId: String): Path = Paths.get(s"extensions/$extensionId.StructureDefinition.json")
}

object OutputPaths {
  given OutputPaths = DefaultOutputPaths
}
