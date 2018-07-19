package com.teambition.kafka.connect.mongo.source

import com.teambition.kafka.connect.mongo.database.DatabaseReader
import org.slf4j.LoggerFactory

/**
 * @author Xu Jingxin
 */
class MongoSourceTask : AbstractMongoSourceTask() {
    override val log = LoggerFactory.getLogger(MongoSourceTask::class.java)!!
    // How many times will a process retries before quit
    private val maxErrCount = 5
    private val databaseReaders = mutableMapOf<String, DatabaseReader>()

    override fun start(props: Map<String, String>) {
        super.start(props)
        databases.forEach { startReader(it, 0) }
    }

    private fun loadReader(db: String): DatabaseReader {
        val partition = getPartition(db)
        val timeOffset = context.offsetStorageReader().offset(partition)
        val start = if (!(timeOffset == null || timeOffset.isEmpty())) timeOffset[db] as String else null
        val startOffset = MongoSourceOffset(start)
        log.info("Start database reader for db: {}, start from: {}",
            db,
            startOffset.toString())
        return DatabaseReader(uri, db, startOffset, messages, initialImport)
    }

    // Create a new DatabaseReader thread for
    private fun startReader(db: String, errCount: Int = 0) {
        if (errCount > maxErrCount) {
            unrecoverable = Exception("Can not execute database reader!")
            return
        }
        val startTime = System.currentTimeMillis()
        val reader = loadReader(db)
        databaseReaders[db] = reader
        val t = Thread(reader)
        val uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, throwable ->
            throwable.printStackTrace()
            log.error("Error when read data from db: {}", db)
            var errCnt = errCount + 1
            // Reset error count if the task executed more than 5 minutes
            if ((System.currentTimeMillis() - startTime) > 600000) {
                errCnt = 0
            }
            startReader(db, errCnt)
        }
        t.uncaughtExceptionHandler = uncaughtExceptionHandler
        t.start()
    }
}