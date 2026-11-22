package main

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

func connectVoice(t *testing.T, f *fixture, path, token string) *websocket.Conn {
	t.Helper()
	url := strings.Replace(f.server.URL, "http:", "ws:", 1) + path
	c, _, err := websocket.Dial(context.Background(), url, &websocket.DialOptions{HTTPHeader: http.Header{"Authorization": []string{"Bearer " + token}}, Subprotocols: []string{voiceProtocol}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.CloseNow() })
	return c
}

func sendVoice(t *testing.T, c *websocket.Conn, kind websocket.MessageType, value []byte) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	if err := c.Write(ctx, kind, value); err != nil {
		t.Fatal(err)
	}
}
func readVoice(t *testing.T, c *websocket.Conn) (websocket.MessageType, []byte) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	kind, b, err := c.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	return kind, b
}

func TestVoiceRelayOffersAndCopiesTextAndBinaryBothWays(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	device := connectVoice(t, f, "/voice/device", f.device)
	client := connectVoice(t, f, "/voice/client", f.token)
	var offer voiceControl
	_, b := readVoice(t, device)
	if json.Unmarshal(b, &offer) != nil || offer.Type != "voice_offer" || offer.Token != f.token {
		t.Fatalf("bad offer: %s", b)
	}
	sendVoice(t, device, websocket.MessageText, []byte(`{"type":"voice_accept"}`))
	for _, pair := range []struct {
		src, dst *websocket.Conn
		kind     websocket.MessageType
		body     []byte
	}{
		{client, device, websocket.MessageText, []byte(`{"type":"HELLO"}`)},
		{client, device, websocket.MessageBinary, []byte{0, 1, 2, 255}},
		{device, client, websocket.MessageText, []byte(`{"type":"READY"}`)},
		{device, client, websocket.MessageBinary, []byte{255, 2, 1, 0}},
	} {
		sendVoice(t, pair.src, pair.kind, pair.body)
		kind, got := readVoice(t, pair.dst)
		if kind != pair.kind || string(got) != string(pair.body) {
			t.Fatalf("frame changed: %v %x", kind, got)
		}
	}
}

func TestVoiceRelayRejectsAndClosesClient(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	device := connectVoice(t, f, "/voice/device", f.device)
	client := connectVoice(t, f, "/voice/client", f.token)
	_, b := readVoice(t, device)
	var offer voiceControl
	_ = json.Unmarshal(b, &offer)
	sendVoice(t, device, websocket.MessageText, []byte(`{"type":"voice_reject"}`))
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, _, err := client.Read(ctx); err == nil {
		t.Fatal("rejected client stayed open")
	}
}

func TestVoiceRelayPeerCloseClosesOtherLeg(t *testing.T) {
	f := newFixture(t)
	f.pair(t)
	device := connectVoice(t, f, "/voice/device", f.device)
	client := connectVoice(t, f, "/voice/client", f.token)
	_, _ = readVoice(t, device)
	sendVoice(t, device, websocket.MessageText, []byte(`{"type":"voice_accept"}`))
	_ = client.CloseNow()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if _, _, err := device.Read(ctx); err == nil {
		t.Fatal("device leg stayed open")
	}
}
