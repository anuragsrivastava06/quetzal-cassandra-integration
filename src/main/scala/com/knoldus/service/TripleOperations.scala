package com.knoldus.service

import com.google.inject.Inject
import com.knoldus.helper.QueryHelper
import com.knoldus.model.{CassandraCluster, PredicateInfo, Triple}
import com.knoldus.{DPH, sparkSession}
import org.apache.spark.sql.{Encoder, Encoders}

@Inject
class TripleOperations()(
  cassandraCluster: CassandraCluster,
  predicateHashing: PredicateHashing,
  directPredicateHashing: DirectPredicateHashing) {

  def storeTriple(triple: Triple): Boolean = {

    predicateHashing.getPredicateDetails(triple.predicate) match {
      case Some(columnLocation) =>
        val entityInfo = directPredicateHashing.getUpdateInfo(columnLocation, triple)
        if (entityInfo.isAvailable && entityInfo.id.isEmpty) {
          directPredicateHashing.updateTripleToDPH(triple, entityInfo)
        } else if (entityInfo.isAvailable && entityInfo.id.isDefined) {
          directPredicateHashing.updateViaId(triple, entityInfo)
        } else {
          directPredicateHashing.storeTripleToDPH(triple, "Chemistry", entityInfo)
        }
      case None => storeFreshTriple(triple)
    }
  }

  def storeFreshTriple(triple: Triple): Boolean = {

    val (minColumnValue, maxColumnValue) = predicateHashing.getHashValue(triple.predicate)
    val entityInfo = directPredicateHashing.getEntityInfo(minColumnValue, maxColumnValue, triple)
    val predicateInfo = PredicateInfo(triple.predicate, entityInfo.location.toString)
    if (predicateHashing.storePredicate(predicateInfo)) {
      if (entityInfo.isAvailable) {
        val result = directPredicateHashing.updateTripleToDPH(triple, entityInfo)
        result
      } else {
        directPredicateHashing.storeTripleToDPH(triple, "Chemistry", entityInfo)
      }
    } else {
      throw new Exception("Unable to Store triple in predicate_mapping Table")
    }
  }

  def fetchObject(subject: String, predicate: String): Option[Triple] = {
    implicit val tripleEncoder: Encoder[Triple] = Encoders.product[Triple]
    directPredicateHashing.registerDPHTable(sparkSession)
    val location = predicateHashing.getPredicateDetails(predicate)
    location match {
      case Some(predicateLocation) => sparkSession.sql(
        s"""Select entity AS entry, prop$predicateLocation
 AS predicate,
           | val$predicateLocation AS value from $DPH where entity = '$subject
'
           |and prop$predicateLocation = '$predicate' limit 1""".
          stripMargin).as[Triple]
        .collect().headOption
      case None => None
    }
  }
}

object TripleOperations {
  def main(args: Array[String]): Unit = {
    val queryHelper = new QueryHelper
    val cassandraCluster = new CassandraCluster(queryHelper)
    val hashing = new Hashing
    val predicateHashing = new PredicateHashing()(cassandraCluster, hashing)
    val directPredicateHashing = new DirectPredicateHashing(cassandraCluster, queryHelper)
    cassandraCluster.createPredicateSchema()
    cassandraCluster.createDPHTable()
    val tripleOperations = new TripleOperations()(cassandraCluster, predicateHashing, directPredicateHashing)
    tripleOperations.storeTriple(Triple("Entity2", "Predicate2", "Shubharambh+++"))
  }
}