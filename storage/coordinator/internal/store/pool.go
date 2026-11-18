package store

import (
	"net/http"
	"sync"
	"time"
)

// Pool hands out one Forwarder per storage-node URL. All of them share one HTTP
// transport, so idle connections to every node are reused.
type Pool struct {
	mu        sync.Mutex
	transport *http.Transport
	timeout   time.Duration
	clients   map[string]*Forwarder
}

// NewPool creates a Pool whose clients time out each call after timeout.
func NewPool(timeout time.Duration) *Pool {
	return &Pool{transport: newTransport(), timeout: timeout, clients: make(map[string]*Forwarder)}
}

// For returns the client for the node at url.
func (p *Pool) For(url string) *Forwarder {
	p.mu.Lock()
	defer p.mu.Unlock()
	f, ok := p.clients[url]
	if !ok {
		f = newForwarder(url, p.timeout, p.transport)
		p.clients[url] = f
	}
	return f
}
