package ankka

// Socket is an open socket of a socket route (1.9): frames the client sends are received, frames
// the handler sends reach the client, and the socket closes when the handler returns.
type Socket struct {
	in     chan string
	out    func(string) error
	closed chan struct{}
	reason string
}
