// Application-managed provisioning for Namastack Once 0.1.0.
// Run with mongosh against the application's database before processing traffic.
// Once does not execute this script automatically. Do not add a TTL index: times are BSON Int64 microseconds.
db.createCollection("once_marker");
db.once_marker.createIndex({expiresAtMicros: 1, _id: 1}, {name: "ix_once_marker_expires_at"});
