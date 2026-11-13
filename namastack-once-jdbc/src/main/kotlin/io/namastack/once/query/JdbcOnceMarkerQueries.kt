package io.namastack.once.query

/**
 * SQL statements for reservations and atomic cleanup batches in one marker table.
 *
 * All cleanup variants bind the fixed UTC `:cutoff` and positive `:batchSize`.
 * The caller executes one statement and uses its affected-row count to detect an empty batch.
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
interface JdbcOnceMarkerQueries {
    /**
     * INSERT reserving one identity before protected application work begins.
     *
     * Parameters are `:keyHash`, `:scope`, `:createdAt` and `:expiresAt`; timestamps contain UTC fields.
     */
    val insertMarker: String

    /**
     * Atomic DELETE removing at most one batch of markers eligible at the supplied cutoff.
     *
     * Parameters are the inclusive UTC `:cutoff` and positive `:batchSize`.
     */
    val deleteExpiredBatch: String
}
