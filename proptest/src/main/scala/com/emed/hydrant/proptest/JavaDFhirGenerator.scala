package com.emed.hydrant.proptest

import com.emed.hydrant.Definitions

import java.util.Optional
import scala.jdk.OptionConverters.RichOption

class JavaDFhirGenerator(defns: Definitions) {
  private val dfhirGen = DFhirGenerator(defns)
  def getSample(templateId: String, maxSize: Int = 100): Optional[String] =
    dfhirGen.generateSample(templateId, maxSize).map(_.noSpaces).toJava
}
