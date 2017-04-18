/***********************************************************************
* Copyright (c) 2013-2017 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.arrow.vector

import java.io.Closeable

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.complex.NullableMapVector
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.pojo.ArrowType.{FixedSizeList, FloatingPoint}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field}
import org.locationtech.geomesa.arrow.vector.SimpleFeatureVector.GeometryPrecision.GeometryPrecision
import org.locationtech.geomesa.features.arrow.ArrowSimpleFeature
import org.locationtech.geomesa.features.serialization.ObjectType
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.opengis.feature.simple.{SimpleFeature, SimpleFeatureType}

import scala.annotation.tailrec

/**
  * Abstraction for using simple features in Arrow vectors
  *
  * @param sft simple feature type
  * @param underlying underlying arrow vector
  * @param dictionaries map of field names to dictionary values, used for dictionary encoding fields.
  *                     All values must be provided up front.
  * @param allocator buffer allocator
  */
class SimpleFeatureVector private (val sft: SimpleFeatureType,
                                   val underlying: NullableMapVector,
                                   val dictionaries: Map[String, ArrowDictionary],
                                   val precision: GeometryPrecision)
                                  (implicit allocator: BufferAllocator) extends Closeable {

  // TODO user data at feature and schema level

  private var maxIndex = underlying.getValueCapacity - 1

  // note: writer creates the map child vectors based on the sft, and should be instantiated before the reader
  val writer = new Writer(this)
  val reader = new Reader(this)

  /**
    * double underlying vector capacity
    */
  def expand(): Unit = {
    underlying.reAlloc()
    maxIndex = underlying.getValueCapacity - 1
  }

  /**
    * Clear any simple features currently stored in the vector
    */
  def reset(): Unit = underlying.getMutator.setValueCount(0)

  override def close(): Unit = {
    underlying.close()
    writer.close()
  }

  class Writer(vector: SimpleFeatureVector) {
    private [SimpleFeatureVector] val arrowWriter = vector.underlying.getWriter
    private val idWriter = ArrowAttributeWriter("id", Seq(ObjectType.STRING), classOf[String], vector.underlying, None, null)
    private val attributeWriters = ArrowAttributeWriter(sft, vector.underlying, dictionaries, precision).toArray

    def set(index: Int, feature: SimpleFeature): Unit = {
      while (index > vector.maxIndex ) {
        vector.expand()
      }
      arrowWriter.setPosition(index)
      arrowWriter.start()
      idWriter.apply(index, feature.getID)
      var i = 0
      while (i < attributeWriters.length) {
        attributeWriters(i).apply(index, feature.getAttribute(i))
        i += 1
      }
      arrowWriter.end()
    }

    def setValueCount(count: Int): Unit = {
      arrowWriter.setValueCount(count)
      attributeWriters.foreach(_.setValueCount(count))
    }

    private [vector] def close(): Unit = {
      idWriter.close()
      attributeWriters.foreach(_.close())
      arrowWriter.close()
    }
  }

  class Reader(vector: SimpleFeatureVector) {
    private val idReader = ArrowAttributeReader("id", Seq(ObjectType.STRING), classOf[String], vector.underlying, None, null)
    private val attributeReaders = ArrowAttributeReader(sft, vector.underlying, dictionaries, precision).toArray

    def get(index: Int): ArrowSimpleFeature = new ArrowSimpleFeature(sft, idReader, attributeReaders, index)

    def getValueCount: Int = vector.underlying.getAccessor.getValueCount
  }
}

object SimpleFeatureVector {

  val DefaultCapacity = 8096

  object GeometryPrecision extends Enumeration {
    type GeometryPrecision = Value
    val Float, Double = Value
  }

  /**
    * Create a new simple feature vector
    *
    * @param sft simple feature type
    * @param dictionaries map of field names to dictionary values, used for dictionary encoding fields.
    *                     All values must be provided up front.
    * @param allocator buffer allocator
    * @return
    */
  def create(sft: SimpleFeatureType,
             dictionaries: Map[String, ArrowDictionary],
             precision: GeometryPrecision = GeometryPrecision.Double,
             capacity: Int = DefaultCapacity)
            (implicit allocator: BufferAllocator): SimpleFeatureVector = {
    val underlying = new NullableMapVector(sft.getTypeName, allocator, null, null)
    val vector = new SimpleFeatureVector(sft, underlying, dictionaries, precision)
    // set capacity after all child vectors have been created by the writers, then allocate
    underlying.setInitialCapacity(capacity)
    underlying.allocateNew()
    vector
  }

  /**
    * Creates a simple feature vector based on an existing arrow vector
    *
    * @param vector arrow vector
    * @param dictionaries map of field names to dictionary values, used for dictionary encoding fields.
    *                     All values must be provided up front.
    * @param allocator buffer allocator
    * @return
    */
  def wrap(vector: NullableMapVector,
           dictionaries: Map[String, ArrowDictionary])
          (implicit allocator: BufferAllocator): SimpleFeatureVector = {
    import scala.collection.JavaConversions._
    val attributes = vector.getField.getChildren.collect {
      // filter out feature id from attributes
      case field if field.getName != "id" => field.getName
    }
    val sft = SimpleFeatureTypes.createType(vector.getField.getName, attributes.mkString(","))
    val geomVector = Option(vector.getChild(SimpleFeatureTypes.encodeDescriptor(sft, sft.getGeometryDescriptor)))
    val precision = geomVector.map(v => precisionFromField(v.getField)).getOrElse(GeometryPrecision.Double)
    new SimpleFeatureVector(sft, vector, dictionaries, precision)
  }

  @tailrec
  private def precisionFromField(field: Field): GeometryPrecision = {
    field.getType match {
      case t: FloatingPoint if t.getPrecision == FloatingPointPrecision.SINGLE => GeometryPrecision.Float
      case t: FloatingPoint if t.getPrecision == FloatingPointPrecision.DOUBLE => GeometryPrecision.Double
      case t =>
        if (!t.isInstanceOf[ArrowType.List] && !t.isInstanceOf[FixedSizeList]) {
          throw new IllegalArgumentException(s"Unexpected field $field")
        } else {
          precisionFromField(field.getChildren.get(0))
        }
    }
  }
}