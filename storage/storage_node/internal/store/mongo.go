// Package store implements the storage node's versioned record store on top of a
// standalone MongoDB.
//
// MongoDB is used purely as a durable, indexed document store for this one node.
// Sharding, replication and failover are implemented by our own coordinator and
// storage-node code (spec decision 2.2-8), so no Mongo replica set is involved.
package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"
	"go.mongodb.org/mongo-driver/v2/x/mongo/driver/topology"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
)

// Errors returned by MongoStore. The HTTP layer maps each one to a status code.
var (
	ErrNotFound           = errors.New("record not found")
	ErrVersionConflict    = errors.New("version conflict")
	ErrPreconditionFailed = errors.New("expectedVersion given but record does not exist")
	ErrInvalidValue       = errors.New("value cannot be stored")
	ErrUnavailable        = errors.New("mongodb unavailable")
)

const collectionName = "records"

// storedDoc is the shape of one document in the "records" collection:
// { _id: key, value: {...}, indexes: {...}, version, updatedAt, deleted }.
//
// Deletes are stored as tombstones (deleted: true, empty value) rather than removed,
// so that they replicate and sync to other nodes of the shard like any other write,
// and so that a re-created key keeps counting versions up instead of restarting at 1.
type storedDoc struct {
	Key       string            `bson:"_id"`
	Value     bson.Raw          `bson:"value"`
	Indexes   map[string]string `bson:"indexes"`
	Version   int64             `bson:"version"`
	UpdatedAt time.Time         `bson:"updatedAt"`
	Deleted   bool              `bson:"deleted,omitempty"`
}

// notDeleted matches live records: tombstones have deleted: true, live records have
// deleted: false or no field at all (documents written before tombstones existed).
func notDeleted() bson.M { return bson.M{"$ne": true} }

// MongoStore stores records in a single MongoDB collection.
type MongoStore struct {
	coll *mongo.Collection
}

// New returns a MongoStore using the "records" collection of db.
func New(db *mongo.Database) *MongoStore {
	return &MongoStore{coll: db.Collection(collectionName)}
}

// EnsureIndexes creates the secondary indexes used by queries. MongoDB treats creating
// an index that already exists (same keys, same options) as a no-op, so this is safe
// to call on every startup.
func (s *MongoStore) EnsureIndexes(ctx context.Context) error {
	fields := []string{"indexes.collection", "indexes.geoCell", "indexes.type", "indexes.status"}
	models := make([]mongo.IndexModel, 0, len(fields))
	for _, f := range fields {
		models = append(models, mongo.IndexModel{Keys: bson.D{{Key: f, Value: 1}}})
	}
	// Recovery sync pages through records in (updatedAt, _id) order.
	models = append(models, mongo.IndexModel{Keys: bson.D{{Key: "updatedAt", Value: 1}, {Key: "_id", Value: 1}}})
	_, err := s.coll.Indexes().CreateMany(ctx, models)
	return classify(err)
}

// Ping checks that MongoDB is reachable (used by /readyz).
func (s *MongoStore) Ping(ctx context.Context) error {
	return classify(s.coll.Database().Client().Ping(ctx, nil))
}

// Get returns the live record stored under key. A tombstone counts as not found.
func (s *MongoStore) Get(ctx context.Context, key string) (model.Record, error) {
	var d storedDoc
	err := s.coll.FindOne(ctx, bson.M{"_id": key, "deleted": notDeleted()}).Decode(&d)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return model.Record{}, ErrNotFound
	}
	if err != nil {
		return model.Record{}, classify(err)
	}
	return d.toRecord()
}

// Put creates or replaces the record under key. The returned bool is true when the
// record was newly created. Every branch is a single atomic MongoDB operation on one
// document, which is what makes compare-and-set safe under concurrency.
func (s *MongoStore) Put(ctx context.Context, key string, req model.PutRequest) (model.Record, bool, error) {
	value, err := jsonToBSON(req.Value)
	if err != nil {
		return model.Record{}, false, err
	}
	now := now()

	switch {
	case req.ExpectedVersion == nil:
		return s.upsert(ctx, key, value, req.Indexes, now)
	case *req.ExpectedVersion == 0:
		return s.insert(ctx, key, value, req.Indexes, now)
	default:
		rec, err := s.compareAndSet(ctx, key, *req.ExpectedVersion, value, req.Indexes, now)
		return rec, false, err
	}
}

// Delete turns the live record under key into a tombstone and returns the tombstone,
// which the coordinator replicates. If expectedVersion is non-nil the record is
// deleted only when its current version matches.
func (s *MongoStore) Delete(ctx context.Context, key string, expectedVersion *int64) (model.Record, error) {
	filter := bson.M{"_id": key, "deleted": notDeleted()}
	if expectedVersion != nil {
		filter["version"] = *expectedVersion
	}
	update := bson.M{
		"$set": bson.M{"deleted": true, "value": bson.D{}, "updatedAt": now()},
		"$inc": bson.M{"version": int64(1)},
	}
	var d storedDoc
	err := s.coll.FindOneAndUpdate(ctx, filter, update, options.FindOneAndUpdate().SetReturnDocument(options.After)).Decode(&d)
	if err == nil {
		return d.toRecord()
	}
	if !errors.Is(err, mongo.ErrNoDocuments) {
		return model.Record{}, classify(err)
	}
	if expectedVersion == nil {
		return model.Record{}, ErrNotFound
	}
	// Nothing deleted under a conditional filter: either the key is missing or the
	// version differs. Look again only to pick the right error.
	exists, err := s.exists(ctx, key)
	if err != nil {
		return model.Record{}, err
	}
	if exists {
		return model.Record{}, ErrVersionConflict
	}
	return model.Record{}, ErrNotFound
}

// Query returns up to the query's limit of records matching it, sorted by key so
// results are deterministic.
func (s *MongoStore) Query(ctx context.Context, q model.QueryRequest) ([]model.Record, error) {
	filter, err := BuildFilter(q)
	if err != nil {
		return nil, err
	}
	opts := options.Find().
		SetSort(bson.D{{Key: "_id", Value: 1}}).
		SetLimit(int64(q.EffectiveLimit()))

	cur, err := s.coll.Find(ctx, filter, opts)
	if err != nil {
		return nil, classify(err)
	}
	defer cur.Close(ctx)

	records := make([]model.Record, 0) // non-nil so it encodes as [] rather than null
	for cur.Next(ctx) {
		var d storedDoc
		if err := cur.Decode(&d); err != nil {
			return nil, err
		}
		rec, err := d.toRecord()
		if err != nil {
			return nil, err
		}
		records = append(records, rec)
	}
	if err := cur.Err(); err != nil {
		return nil, classify(err)
	}
	return records, nil
}

// upsert writes unconditionally: create (or revive a tombstone), or replace and bump
// the version.
func (s *MongoStore) upsert(ctx context.Context, key string, value bson.Raw, indexes map[string]string, now time.Time) (model.Record, bool, error) {
	// ReturnDocument(Before) tells us atomically whether a live record existed; the
	// new record is fully determined by the update, so we build it ourselves.
	opts := options.FindOneAndUpdate().SetUpsert(true).SetReturnDocument(options.Before)
	update := writeUpdate(value, indexes, now)

	// Two concurrent upserts of the same *new* key can both decide to insert; MongoDB
	// lets one win and fails the other with a duplicate-key error. Retrying once is the
	// documented fix: by then the document exists, so the retry becomes an update.
	for attempt := 0; ; attempt++ {
		var before storedDoc
		err := s.coll.FindOneAndUpdate(ctx, bson.M{"_id": key}, update, opts).Decode(&before)
		if mongo.IsDuplicateKeyError(err) && attempt == 0 {
			continue
		}
		if errors.Is(err, mongo.ErrNoDocuments) { // nothing before: inserted at version 1
			before = storedDoc{Deleted: true}
		} else if err != nil {
			return model.Record{}, false, classify(err)
		}
		d := storedDoc{Key: key, Value: value, Indexes: indexes, Version: before.Version + 1, UpdatedAt: now}
		rec, err := d.toRecord()
		return rec, before.Deleted, err
	}
}

// insert is the expectedVersion == 0 case: create only, fail if a live record exists.
// The filter matches only a tombstone, so an existing tombstone is revived (keeping
// its version count) and a missing key is inserted by the upsert; a live record makes
// the upsert collide on _id.
func (s *MongoStore) insert(ctx context.Context, key string, value bson.Raw, indexes map[string]string, now time.Time) (model.Record, bool, error) {
	opts := options.FindOneAndUpdate().SetUpsert(true).SetReturnDocument(options.After)
	var d storedDoc
	err := s.coll.FindOneAndUpdate(ctx, bson.M{"_id": key, "deleted": true}, writeUpdate(value, indexes, now), opts).Decode(&d)
	if mongo.IsDuplicateKeyError(err) {
		return model.Record{}, false, ErrVersionConflict
	}
	if err != nil {
		return model.Record{}, false, classify(err)
	}
	rec, err := d.toRecord()
	return rec, true, err
}

// compareAndSet replaces the record only if its current version equals expected.
// The version check and the write happen inside one findOneAndUpdate, so two callers
// holding the same version can never both succeed: exactly one matches the filter.
func (s *MongoStore) compareAndSet(ctx context.Context, key string, expected int64, value bson.Raw, indexes map[string]string, now time.Time) (model.Record, error) {
	opts := options.FindOneAndUpdate().SetReturnDocument(options.After)
	filter := bson.M{"_id": key, "version": expected, "deleted": notDeleted()}

	var d storedDoc
	err := s.coll.FindOneAndUpdate(ctx, filter, writeUpdate(value, indexes, now), opts).Decode(&d)
	if errors.Is(err, mongo.ErrNoDocuments) {
		// The write was correctly rejected. This extra read is not atomic with it and is
		// used only to choose between 412 (missing) and 409 (different version).
		exists, err := s.exists(ctx, key)
		if err != nil {
			return model.Record{}, err
		}
		if exists {
			return model.Record{}, ErrVersionConflict
		}
		return model.Record{}, ErrPreconditionFailed
	}
	if err != nil {
		return model.Record{}, classify(err)
	}
	return d.toRecord()
}

func (s *MongoStore) exists(ctx context.Context, key string) (bool, error) {
	n, err := s.coll.CountDocuments(ctx, bson.M{"_id": key, "deleted": notDeleted()}, options.Count().SetLimit(1))
	if err != nil {
		return false, classify(err)
	}
	return n > 0, nil
}

// writeUpdate is the update document shared by upsert and compareAndSet: replace the
// value and indexes, stamp the time, and atomically increment the version.
func writeUpdate(value bson.Raw, indexes map[string]string, now time.Time) bson.M {
	return bson.M{
		"$set": bson.M{"value": value, "indexes": indexes, "updatedAt": now, "deleted": false},
		"$inc": bson.M{"version": int64(1)},
	}
}

// now is the timestamp for a write. MongoDB dates have millisecond precision;
// truncating here means the value we return is identical to what a later read returns.
func now() time.Time {
	return time.Now().UTC().Truncate(time.Millisecond)
}

func (d storedDoc) toRecord() (model.Record, error) {
	value, err := bsonToJSON(d.Value)
	if err != nil {
		return model.Record{}, err
	}
	return model.Record{
		Key:       d.Key,
		Value:     value,
		Indexes:   d.Indexes,
		Version:   d.Version,
		UpdatedAt: d.UpdatedAt.UTC(),
		Deleted:   d.Deleted,
	}, nil
}

// jsonToBSON converts the opaque JSON value into a BSON document.
//
// We go through MongoDB's (relaxed) Extended JSON parser rather than json.Unmarshal into
// a map because the latter turns every number into a float64: 480 would come back as
// 480.0 to the Java services. Extended JSON keeps integers as integers and preserves
// key order. (Side effect: keys such as "$date" or "$numberLong" are interpreted as
// Extended JSON type wrappers — domain documents don't use "$"-prefixed keys.)
func jsonToBSON(raw json.RawMessage) (bson.Raw, error) {
	var doc bson.D
	if err := bson.UnmarshalExtJSON(raw, false, &doc); err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidValue, err)
	}
	b, err := bson.Marshal(doc)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidValue, err)
	}
	return b, nil
}

// bsonToJSON converts a stored BSON document back into plain (relaxed) JSON.
func bsonToJSON(raw bson.Raw) (json.RawMessage, error) {
	b, err := bson.MarshalExtJSON(raw, false, false)
	if err != nil {
		return nil, fmt.Errorf("encode stored value: %w", err)
	}
	return b, nil
}

// classify marks errors that mean "MongoDB is unreachable or too slow" so the HTTP layer
// can answer 503 instead of 500.
func classify(err error) error {
	if err == nil {
		return nil
	}
	var sse topology.ServerSelectionError
	if mongo.IsTimeout(err) || mongo.IsNetworkError(err) || errors.As(err, &sse) || errors.Is(err, context.Canceled) {
		return fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	return err
}
