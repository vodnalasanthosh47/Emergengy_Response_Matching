package store

import (
	"context"
	"errors"
	"time"

	"go.mongodb.org/mongo-driver/v2/bson"
	"go.mongodb.org/mongo-driver/v2/mongo"
	"go.mongodb.org/mongo-driver/v2/mongo/options"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/storage_node/internal/model"
)

// The operations in this file are used by the coordinator to keep the nodes of one
// shard identical: replicating the primary's writes, copying data to a recovering
// node, and repairing writes a failed primary never replicated. Unlike Put, they
// store a record exactly as given, version and updatedAt included, because the copy
// must be indistinguishable from the primary's.

// ApplyReplica stores rec as-is if its version is >= the version this node holds,
// and reports whether it was applied. An older rec is ignored, which makes
// replication idempotent and safe when writes arrive out of order. On equal versions
// the incoming (primary's) copy wins, so after a failover the new primary's data
// overwrites a divergent copy with the same version.
func (s *MongoStore) ApplyReplica(ctx context.Context, rec model.Record) (bool, error) {
	set, err := replicaSet(rec)
	if err != nil {
		return false, err
	}
	filter := bson.M{"_id": rec.Key, "version": bson.M{"$lte": rec.Version}}
	opts := options.UpdateOne().SetUpsert(true)

	// If the key exists with a higher version the filter does not match, the upsert
	// tries to insert, and the insert collides on _id: the stored copy is newer.
	// A collision can also be two replica writes racing to create the same new key,
	// so retry once; on the retry the document exists and the filter decides.
	for attempt := 0; ; attempt++ {
		_, err := s.coll.UpdateOne(ctx, filter, bson.M{"$set": set}, opts)
		if mongo.IsDuplicateKeyError(err) {
			if attempt == 0 {
				continue
			}
			return false, nil
		}
		if err != nil {
			return false, classify(err)
		}
		return true, nil
	}
}

// Repair overwrites the record under rec.Key with rec, but only if this node still
// holds localVersion. The condition stops a repair from clobbering a newer write
// that replication delivered while recovery was running.
func (s *MongoStore) Repair(ctx context.Context, rec model.Record, localVersion int64) (bool, error) {
	set, err := replicaSet(rec)
	if err != nil {
		return false, err
	}
	res, err := s.coll.UpdateOne(ctx, bson.M{"_id": rec.Key, "version": localVersion}, bson.M{"$set": set})
	if err != nil {
		return false, classify(err)
	}
	return res.MatchedCount == 1, nil
}

// Purge physically removes the record under key if this node still holds
// localVersion. Used when recovery finds a key the shard's primary has never seen.
func (s *MongoStore) Purge(ctx context.Context, key string, localVersion int64) (bool, error) {
	res, err := s.coll.DeleteOne(ctx, bson.M{"_id": key, "version": localVersion})
	if err != nil {
		return false, classify(err)
	}
	return res.DeletedCount == 1, nil
}

// GetAny returns the record under key, tombstones included.
func (s *MongoStore) GetAny(ctx context.Context, key string) (model.Record, error) {
	var d storedDoc
	err := s.coll.FindOne(ctx, bson.M{"_id": key}).Decode(&d)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return model.Record{}, ErrNotFound
	}
	if err != nil {
		return model.Record{}, classify(err)
	}
	return d.toRecord()
}

// Since returns up to limit records (tombstones included) that come after the cursor
// (since, afterKey) in (updatedAt, _id) order. Ordering on the pair, not on updatedAt
// alone, means records sharing a timestamp are never skipped at a page boundary.
func (s *MongoStore) Since(ctx context.Context, since time.Time, afterKey string, limit int) (model.SyncPage, error) {
	filter := bson.M{"$or": bson.A{
		bson.M{"updatedAt": bson.M{"$gt": since}},
		bson.M{"updatedAt": since, "_id": bson.M{"$gt": afterKey}},
	}}
	opts := options.Find().
		SetSort(bson.D{{Key: "updatedAt", Value: 1}, {Key: "_id", Value: 1}}).
		SetLimit(int64(limit) + 1) // one extra tells us whether another page exists

	cur, err := s.coll.Find(ctx, filter, opts)
	if err != nil {
		return model.SyncPage{}, classify(err)
	}
	defer cur.Close(ctx)

	page := model.SyncPage{Records: make([]model.Record, 0)}
	for cur.Next(ctx) {
		if len(page.Records) == limit {
			page.More = true
			break
		}
		var d storedDoc
		if err := cur.Decode(&d); err != nil {
			return model.SyncPage{}, err
		}
		rec, err := d.toRecord()
		if err != nil {
			return model.SyncPage{}, err
		}
		page.Records = append(page.Records, rec)
	}
	if err := cur.Err(); err != nil {
		return model.SyncPage{}, classify(err)
	}
	if n := len(page.Records); n > 0 {
		page.NextSince, page.NextKey = page.Records[n-1].UpdatedAt, page.Records[n-1].Key
	} else {
		page.NextSince, page.NextKey = since, afterKey
	}
	return page, nil
}

// Stats counts live records and tombstones and reports the newest updatedAt.
func (s *MongoStore) Stats(ctx context.Context) (model.Stats, error) {
	live, err := s.coll.CountDocuments(ctx, bson.M{"deleted": notDeleted()})
	if err != nil {
		return model.Stats{}, classify(err)
	}
	dead, err := s.coll.CountDocuments(ctx, bson.M{"deleted": true})
	if err != nil {
		return model.Stats{}, classify(err)
	}
	maxUpdated, err := s.MaxUpdatedAt(ctx)
	if err != nil {
		return model.Stats{}, err
	}
	return model.Stats{Records: live, Tombstones: dead, MaxUpdatedAt: maxUpdated}, nil
}

// MaxUpdatedAt returns the newest updatedAt on this node (zero if it is empty). The
// node reports it in heartbeats: the coordinator promotes the most up-to-date node
// and starts a recovering node's sync from it.
func (s *MongoStore) MaxUpdatedAt(ctx context.Context) (time.Time, error) {
	var d struct {
		UpdatedAt time.Time `bson:"updatedAt"`
	}
	opts := options.FindOne().SetSort(bson.D{{Key: "updatedAt", Value: -1}}).SetProjection(bson.M{"updatedAt": 1})
	err := s.coll.FindOne(ctx, bson.M{}, opts).Decode(&d)
	if errors.Is(err, mongo.ErrNoDocuments) {
		return time.Time{}, nil
	}
	if err != nil {
		return time.Time{}, classify(err)
	}
	return d.UpdatedAt.UTC(), nil
}

// replicaSet is the $set document that stores rec exactly as given.
func replicaSet(rec model.Record) (bson.M, error) {
	value := bson.Raw(emptyDoc)
	if !rec.Deleted {
		v, err := jsonToBSON(rec.Value)
		if err != nil {
			return nil, err
		}
		value = v
	}
	return bson.M{
		"value":     value,
		"indexes":   rec.Indexes,
		"version":   rec.Version,
		"updatedAt": rec.UpdatedAt.UTC().Truncate(time.Millisecond),
		"deleted":   rec.Deleted,
	}, nil
}

// emptyDoc is the BSON encoding of {}: the value stored in a tombstone.
var emptyDoc = func() []byte { b, _ := bson.Marshal(bson.D{}); return b }()
