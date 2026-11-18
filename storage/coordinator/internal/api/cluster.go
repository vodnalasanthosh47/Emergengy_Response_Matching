package api

import (
	"context"
	"errors"
	"net/http"

	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/model"
	"github.com/vodnalasanthosh47/Emergengy_Response_Matching/storage/coordinator/internal/registry"
)

// NodeRegistry is what the node endpoints need from the registry.
type NodeRegistry interface {
	Register(info model.NodeInfo) (registry.Role, error)
	Heartbeat(info model.NodeInfo) (registry.Role, error)
}

// Cluster groups the cluster-facing dependencies of the handler.
type Cluster struct {
	Nodes NodeRegistry
	// Topology returns the JSON-encodable cluster view for GET /internal/topology.
	Topology func(ctx context.Context) any
}

type roleResponse struct {
	Role registry.Role `json:"role"`
}

// registerNode is POST /internal/nodes/register, called by a storage node on startup
// and whenever the coordinator does not recognise its heartbeat.
func (s *server) registerNode(w http.ResponseWriter, r *http.Request) {
	var info model.NodeInfo
	if err := decodeJSON(w, r, &info); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	role, err := s.cluster.Nodes.Register(info)
	if err != nil {
		s.writeNodeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, roleResponse{Role: role})
}

// heartbeat is POST /internal/nodes/heartbeat. 404 tells the node to register again.
func (s *server) heartbeat(w http.ResponseWriter, r *http.Request) {
	var info model.NodeInfo
	if err := decodeJSON(w, r, &info); err != nil {
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
		return
	}
	role, err := s.cluster.Nodes.Heartbeat(info)
	if err != nil {
		s.writeNodeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, roleResponse{Role: role})
}

// topology is GET /internal/topology: shards, their nodes (role, state, last
// heartbeat) and each node's record count.
func (s *server) topology(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"shards": s.cluster.Topology(r.Context())})
}

func (s *server) writeNodeError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, registry.ErrUnknownNode):
		writeError(w, http.StatusNotFound, codeNotFound, err.Error())
	case errors.Is(err, registry.ErrInvalidNode):
		writeError(w, http.StatusBadRequest, codeInvalidRequest, err.Error())
	default:
		s.log.Error("node registry error", "err", err)
		writeError(w, http.StatusInternalServerError, codeInternalError, "internal error")
	}
}
