package edu.washington.cs.laragraphulo.api

import edu.washington.cs.laragraphulo.opt.*
import edu.washington.cs.laragraphulo.util.GraphuloUtil
import edu.washington.cs.laragraphulo.util.SkviToIteratorAdapter
import org.apache.accumulo.core.client.BatchWriterConfig
import org.apache.accumulo.core.client.IteratorSetting
import org.apache.accumulo.core.data.*
import org.apache.accumulo.core.iterators.IteratorEnvironment
import org.apache.accumulo.core.iterators.OptionDescriber
import org.apache.accumulo.core.iterators.SortedKeyValueIterator
import org.apache.accumulo.core.security.Authorizations
import java.io.Serializable


class TupleOpSerializer : Serializer<TupleOpSetting, TupleOpSetting> {
  override fun serializeToString(obj: TupleOpSetting): String =
      SerializationUtil.serializeBase64(obj)
  @Suppress("UNCHECKED_CAST")
  override fun deserializeFromString(str: String): TupleOpSetting =
      SerializationUtil.deserializeBase64(str) as TupleOpSetting
  companion object {
    val INSTANCE = TupleOpSerializer()
  }
}
/** This is what we need to run a TupleOp stack in Accumulo. */
data class TupleOpSetting(
    val tupleOp: TupleOp,
    /** Name of the table that we're scanning over. */
    val thisTable: Table,
    val accumuloConfig: AccumuloConfig
) : Serializable

class TupleOpSKVI : DelegatingIterator(), OptionDescriber {
  companion object : SerializerSetting<TupleOpSetting>(TupleOpSKVI::class.java) {
//    const val OPT_THIS_TABLE = "THIS_TABLE"
  }

  override fun initDelegate(source: SortedKeyValueIterator<Key, Value>, options: Map<String, String>, env: IteratorEnvironment): SKVI {
    val (store,thisTable,accumuloConfig) = deserializeFromOptions(options)

    // replace Load statements with this iterator and RemoteSourceIterators
    val baseTables = store.getBaseTables()
    // get schemas of base tables - assume all the schemas are set.
    val baseTablesSchemas = baseTables.map { table ->
      table to accumuloConfig.getSchema(table)
    }.toMap()

    // what about the PhysicalSchemas?
    // store the PSchema of each table as a table config entry
    // need to decide on physical schemas of pipeline output
    // ^-- use default pschema - don't worry about the grouping problem right now

    val tupleIters: Map<Table, TupleIterator> = baseTablesSchemas.mapValues { (table,ps) ->
      val skvi = if (table == thisTable) source else {
        // create RemoteSourceIterators for each other base table and wrap them to be TupleIterators
        val remoteOpts = accumuloConfig.basicRemoteOpts(remoteTable = table)
        val rsi = RemoteSourceIterator()
        rsi.init(null, remoteOpts, env)
        rsi
      }
      KvToTupleAdapter(ps, SkviToIteratorAdapter(skvi))
    }
    // also change Sorts to do nothing
    val storeLoaded = store.instantiateLoadTupleIterator(tupleIters).disableFullResorts()

    //  instantiate Stores with remote write iterators
    // for now, only check if the last operator is a Store
    if (storeLoaded is TupleOp.Store) {
      val runBeforeStore: TupleIterator = storeLoaded.p.run(env)
      val skviBeforeStore = KvToSkviAdapter(TupleToKvAdapter(storeLoaded.resultSchema.defaultPSchema(), runBeforeStore))

      val remoteOpts = accumuloConfig.basicRemoteOpts(remoteTable = storeLoaded.table)
      val rwi = RemoteWriteIterator()
      rwi.init(skviBeforeStore, remoteOpts, env)
      return rwi
    } else {
      val ti = storeLoaded.run(env)
      return KvToSkviAdapter(TupleToKvAdapter(storeLoaded.resultSchema.defaultPSchema(), ti))
    }
  }

  override fun describeOptions(): OptionDescriber.IteratorOptions {
    return OptionDescriber.IteratorOptions("TupleOpSKVI",
        "constructs a new Serializer<TupleOp>, uses it to deserialize a TupleOp payload, " +
            "and constructs a TupleIterator stack out of it",
        mapOf(SerializerSetting.OPT_SERIALIZED_DATA to "the serialized TupleOp",
            SerializerSetting.OPT_SERIALIZER_CLASS to "the class that can deserialize the TupleOp; " +
                "must have a no-args constructor"),
//            OPT_THIS_TABLE to "the name of the table that this scan is attached to"),
        null)
  }
  override fun validateOptions(options: Map<String, String>): Boolean {
    deserializeFromOptions(options)
//    if (OPT_THIS_TABLE !in options) throw IllegalArgumentException("no $OPT_THIS_TABLE")
    return true
  }
}


/** Execute a query on the Accumulo pointed to by this AccumuloConfig */
fun AccumuloConfig.execute(query: TupleOp.Store): Long {
  println("Execute Query: $query")
  val pipelines: List<TupleOp.Store> = query.splitPipeline()
  print("Pipelines to execute: ")
  println(pipelines.joinToString("\n","[\n","\n]"))

  val totalEntries = pipelines.map {
    val oneBaseTable = it.getBaseTables().first()
    print("On $oneBaseTable: ")
    val tos = TupleOpSetting(it, oneBaseTable, this)
    tos.executeSingle()
  }.reduce(Long::plus)

  println("[[[$totalEntries total entries for pipeline]]]")
  return totalEntries
}

/** Execute a single query fragment on the Accumulo pointed to by this AccumuloConfig */
fun TupleOpSetting.executeSingle(): Long {
  val ac = this.accumuloConfig
  val itset: IteratorSetting = TupleOpSKVI.iteratorSetting(TupleOpSerializer.INSTANCE, this, 25)
  val store = this.tupleOp

  if (store is TupleOp.Store) {
    print("Create ${store.table}. ")
    ac.connector.tableOperations().create(store.table)
    ac.setSchema(store.table, store.resultSchema.defaultPSchema()) // matches that in TupleOpSKVI
  }
  println("Schema ${store.resultSchema.defaultPSchema()}. ")

  return ac.connector.createBatchScanner(this.thisTable, Authorizations.EMPTY, 15).use { bs ->
    val ranges = listOf(Range())
    bs.setRanges(ranges)
    bs.addScanIterator(itset)
    var totalEntries = 0L
    var count = 0

    println("Execute ${this.tupleOp}")
    bs.iterator().forEach { (k, v) ->
      val numEntries = RemoteWriteIterator.decodeValue(v, null)
      println("[$numEntries entries] ${k.toStringNoTime()} -> $v")
      totalEntries += numEntries
      count++
    }
    if (count != 1)
      println("[[$totalEntries total entries]]")
    totalEntries
  }
}

/** Attach a TupleOp to an Accumulo table. */
fun TupleOpSetting.attachIterator(priority: Int = 22) {
  val ac = this.accumuloConfig
  val itset: IteratorSetting = TupleOpSKVI.iteratorSetting(TupleOpSerializer.INSTANCE, this, priority)
  val store = this.tupleOp

  if (store is TupleOp.Store) {
    print("Create ${store.table}. ")
    ac.connector.tableOperations().create(store.table)
    ac.setSchema(store.table, store.resultSchema.defaultPSchema()) // matches that in TupleOpSKVI
  }
  println("Schema ${store.resultSchema.defaultPSchema()}. ")

  GraphuloUtil.applyIteratorSoft(itset, ac.connector.tableOperations(), this.thisTable)
}


fun AccumuloConfig.ingestData(table: Table, ps: PSchema, data: Iterable<Tuple>, deleteIfExists: Boolean) {
  GraphuloUtil.recreateTables(this.connector, deleteIfExists, table)
  val iter = TupleToKvAdapter(ps, TupleIterator.DataTupleIterator(ps, data))
  this.setSchema(table, ps)
  this.connector.createBatchWriter(table, BatchWriterConfig().setMaxWriteThreads(15)).use { bw ->
    var r: ByteSequence = ArrayByteSequence(byteArrayOf())
    var m = Mutation(byteArrayOf())
    for ((k, v) in iter) {
      val kr = k.rowData
      if (kr != r) {
        if (m.size() > 0) bw.addMutation(m)
        m = Mutation(kr.toArray())
        r = kr
      }
      m.put(k.columnFamily, k.columnQualifier, k.columnVisibilityParsed, k.timestamp, v)
    }
    if (m.size() > 0) bw.addMutation(m)
  }
}

fun AccumuloConfig.scanAccumulo(
    table: Table, range: Range = Range()
): Iterator<Tuple> {
  val ps = this.getSchema(table)
  val scanner = this.connector.createScanner(table, Authorizations.EMPTY)
  scanner.range = range
  return KvToTupleAdapter(ps, scanner.asKvIterator())
}

fun AccumuloConfig.clone(table1: Table, table2: Table) {
  this.connector.tableOperations().clone(table1, table2,
      true, null, null)
}

