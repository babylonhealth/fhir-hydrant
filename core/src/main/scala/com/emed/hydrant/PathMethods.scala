package com.emed.hydrant

object PathMethods {
  extension (id: String) {

    def tokens: Array[String] = id.split('.')

    def initialSegments: List[String] =
      tokens.foldLeft(List.empty[String]) {
        case (h :: t, next) => s"$h.$next" :: h :: t
        case (Nil, next)    => next :: Nil
      }

    def initialSegment(other: String): Boolean =
      (other startsWith id) && (other.length > id.length) && (other.charAt(id.length) == '.')

    def initialSegmentOrEq(other: String): Boolean = (id == other) || initialSegment(other)

    // Only use if already checked initialSegment
    def deepSegment(other: String): Boolean = other.drop(id.length + 1).contains('.')

    def slicedBy(other: String): Boolean =
      (other startsWith id) && (other.length > id.length) && (other.charAt(id.length) == ':')

    def slicedOrInitialSegment(other: String): Boolean =
      (id == other) || (
        (other startsWith id) && (other.length > id.length) && (extensionTokens contains other.charAt(id.length))
      )

    // Only use if already checked slicedBy!
    def deepSlice(other: String): Boolean = other.drop(id.length).contains('.')

    def slicedHereBy(other: String): Boolean =
      slicedBy(other) && !deepSlice(other)

    def isLastSliced: Boolean = tokens.last.contains(':')

    def --(other: String): Option[String] = if (other initialSegment id) Some(id.drop(other.length + 1)) else None

    def removeLastTokens(other: String): Option[String] = {
      val idTokens    = id.tokens
      val otherTokens = other.tokens.filter(_ != "")
      if (idTokens endsWith (otherTokens))
        Some(idTokens.dropRight(otherTokens.length).mkString("."))
      else None
    }

    // Slight assumption that the first element of a path is the resource type, but I think its true
    def toResourceType = id.firstToken.tokenPath

    def firstToken: String = id.takeWhile(_ != '.')

    def lastToken: String = tokens.last

    def toPath: String = tokens.map(_.tokenPath).mkString(".")

    def withoutLastSlice: String = {
      val ts = tokens
      if (ts.length > 1)
        s"${ts.init.mkString(".")}.${ts.last.tokenPath}"
      else if (ts.length == 1)
        ts.head.tokenPath
      else ""
    }

    def dropLastToken: String = {
      val ts = tokens
      if (ts.length > 1) ts.init.mkString(".") else id
    }

    def tokenPath = id.takeWhile(_ != ':')

    def tokenSliceName = id.dropWhile(_ != ':').drop(1)
  }
}
