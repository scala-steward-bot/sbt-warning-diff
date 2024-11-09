package warning_diff.rdf

trait RangeCompat { self: Range =>
  final def toTupleOption = Range.unapply(self)
}