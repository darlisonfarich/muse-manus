package main

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const voiceProtocol = "mika-relay.v2"

type voiceControl struct {
	Type  string `json:"type"`
	Token string `json:"token,omitempty"`
}

type voiceDeviceLink struct {
	conn     *websocket.Conn
	answers  chan voiceControl
	incoming chan voicePacket
}
type voicePacket struct {
	kind    websocket.MessageType
	payload []byte
}

func (g *gateway) voiceDeviceEndpoint(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || r.Header.Get("Origin") != "" || !matches(bearer(r), g.store.view().DeviceHash) {
		failure(w, 401, "invalid_device_token")
		return
	}
	if !hasVoiceProtocol(r) {
		failure(w, 426, "update_phone_and_gateway")
		return
	}
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{voiceProtocol}})
	if err != nil {
		return
	}
	defer c.CloseNow()
	// O limite tem de caber um frame de audio do hub (segmentos MP3 em
	// pedacos de 64 KiB): 4 KiB derrubava a conexao no primeiro segmento.
	c.SetReadLimit(2 * maxBody)
	g.mu.Lock()
	if !matches(bearer(r), g.store.view().DeviceHash) {
		g.mu.Unlock()
		return
	}
	link := &voiceDeviceLink{conn: c, answers: make(chan voiceControl, 1), incoming: make(chan voicePacket, 16)}
	previous := g.voiceDevice
	g.voiceDevice = link
	g.mu.Unlock()
	if previous != nil {
		_ = previous.conn.Close(websocket.StatusPolicyViolation, "Replaced")
	}
	defer func() {
		g.mu.Lock()
		if g.voiceDevice == link {
			g.voiceDevice = nil
		}
		g.mu.Unlock()
	}()
	ctx := r.Context()
	defer close(link.incoming)
	for {
		kind, b, err := c.Read(ctx)
		if err != nil {
			return
		}
		var f voiceControl
		if kind == websocket.MessageText && json.Unmarshal(b, &f) == nil && (f.Type == "voice_accept" || f.Type == "voice_reject") {
			select {
			case link.answers <- f:
			default:
			}
		} else {
			select {
			case link.incoming <- voicePacket{kind, b}:
			case <-ctx.Done():
				return
			}
		}
	}
}

func (g *gateway) voiceClientEndpoint(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet || r.Header.Get("Origin") != "" || bearer(r) == "" {
		failure(w, 401, "invalid_access_token")
		return
	}
	if !hasVoiceProtocol(r) {
		failure(w, 426, "update_phone_and_gateway")
		return
	}
	client, err := websocket.Accept(w, r, &websocket.AcceptOptions{Subprotocols: []string{voiceProtocol}})
	if err != nil {
		return
	}
	defer client.CloseNow()
	client.SetReadLimit(2 * maxBody)
	g.mu.Lock()
	device := g.voiceDevice
	g.mu.Unlock()
	if device == nil {
		_ = client.Close(websocket.StatusTryAgainLater, "device_offline")
		return
	}
	g.mu.Lock()
	if g.voiceBusy || g.voiceDevice != device {
		g.mu.Unlock()
		_ = client.Close(websocket.StatusTryAgainLater, "voice_busy")
		return
	}
	g.voiceBusy = true
	g.mu.Unlock()
	defer func() { g.mu.Lock(); g.voiceBusy = false; g.mu.Unlock() }()
	for {
		select {
		case <-device.answers:
		default:
			goto drained
		}
	}
drained:
	if err := writeVoiceControl(device.conn, voiceControl{Type: "voice_offer", Token: bearer(r)}); err != nil {
		_ = client.Close(websocket.StatusTryAgainLater, "device_offline")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	var answer voiceControl
	select {
	case answer = <-device.answers:
	case <-ctx.Done():
	}
	cancel()
	if answer.Type != "voice_accept" {
		_ = client.Close(websocket.StatusPolicyViolation, "voice_rejected")
		return
	}
	voiceRelay(client, device)
}

func hasVoiceProtocol(r *http.Request) bool {
	for _, p := range strings.Split(r.Header.Get("Sec-WebSocket-Protocol"), ",") {
		if strings.TrimSpace(p) == voiceProtocol {
			return true
		}
	}
	return false
}

func writeVoiceControl(c *websocket.Conn, f voiceControl) error {
	b, err := json.Marshal(f)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return c.Write(ctx, websocket.MessageText, b)
}

func voiceRelay(client *websocket.Conn, device *voiceDeviceLink) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var once sync.Once
	closeBoth := func() {
		once.Do(func() {
			_ = client.Close(websocket.StatusNormalClosure, "Peer closed")
			_ = device.conn.Close(websocket.StatusNormalClosure, "Peer closed")
		})
	}
	var wg sync.WaitGroup
	pipe := func(src, dst *websocket.Conn) {
		defer wg.Done()
		for {
			kind, payload, err := src.Read(ctx)
			if err != nil {
				cancel()
				closeBoth()
				return
			}
			writeCtx, stop := context.WithTimeout(ctx, 5*time.Second)
			err = dst.Write(writeCtx, kind, payload)
			stop()
			if err != nil {
				cancel()
				closeBoth()
				return
			}
		}
	}
	wg.Add(2)
	go pipe(client, device.conn)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-ctx.Done():
				return
			case p, ok := <-device.incoming:
				if !ok {
					cancel()
					closeBoth()
					return
				}
				writeCtx, stop := context.WithTimeout(ctx, 5*time.Second)
				err := client.Write(writeCtx, p.kind, p.payload)
				stop()
				if err != nil {
					cancel()
					closeBoth()
					return
				}
			}
		}
	}()
	ticker := time.NewTicker(10 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			wg.Wait()
			return
		case <-ticker.C:
			pingCtx, stop := context.WithTimeout(ctx, 5*time.Second)
			errA := client.Ping(pingCtx)
			errB := device.conn.Ping(pingCtx)
			stop()
			if errA != nil || errB != nil {
				cancel()
				closeBoth()
				wg.Wait()
				return
			}
		}
	}
}
