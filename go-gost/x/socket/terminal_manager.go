package socket

import (
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sync"
	"time"

	"github.com/creack/pty"
)

const (
	maxTerminalSessions = 2
	maxTerminalInput    = 64 * 1024
	terminalIdleTimeout = 10 * time.Minute
	terminalMaxDuration = 60 * time.Minute
)

type terminalEventSender func(eventType string, data map[string]interface{})

type TerminalManager struct {
	mu       sync.Mutex
	sessions map[string]*terminalSession
	send     terminalEventSender
}

type terminalSession struct {
	id           string
	command      *exec.Cmd
	pty          *os.File
	startedAt    time.Time
	lastActivity time.Time
	mu           sync.Mutex
	closeOnce    sync.Once
}

type terminalOpenRequest struct {
	SessionID string `json:"sessionId"`
	Cols      uint16 `json:"cols"`
	Rows      uint16 `json:"rows"`
}

type terminalInputRequest struct {
	SessionID string `json:"sessionId"`
	Data      string `json:"data"`
}

type terminalResizeRequest struct {
	SessionID string `json:"sessionId"`
	Cols      uint16 `json:"cols"`
	Rows      uint16 `json:"rows"`
}

type terminalCloseRequest struct {
	SessionID string `json:"sessionId"`
	Reason    string `json:"reason"`
}

func NewTerminalManager(sender terminalEventSender) *TerminalManager {
	return &TerminalManager{sessions: make(map[string]*terminalSession), send: sender}
}

func (m *TerminalManager) Open(request terminalOpenRequest) error {
	if !validTerminalSessionID(request.SessionID) {
		return errors.New("invalid terminal session")
	}
	m.mu.Lock()
	if len(m.sessions) >= maxTerminalSessions {
		m.mu.Unlock()
		return errors.New("terminal session limit reached")
	}
	if _, exists := m.sessions[request.SessionID]; exists {
		m.mu.Unlock()
		return errors.New("terminal session already exists")
	}
	m.mu.Unlock()

	shell, err := detectShell()
	if err != nil {
		return err
	}
	cols, rows := normalizeTerminalSize(request.Cols, request.Rows)
	command := exec.Command(shell)
	command.Dir = "/"
	command.Env = append(os.Environ(), "TERM=xterm-256color", "HISTFILE=/dev/null")
	ptmx, err := pty.StartWithSize(command, &pty.Winsize{Cols: cols, Rows: rows})
	if err != nil {
		return fmt.Errorf("start terminal: %w", err)
	}

	now := time.Now()
	session := &terminalSession{
		id: request.SessionID, command: command, pty: ptmx,
		startedAt: now, lastActivity: now,
	}
	m.mu.Lock()
	m.sessions[request.SessionID] = session
	m.mu.Unlock()
	m.send("TerminalOpened", map[string]interface{}{
		"sessionId": request.SessionID,
		"shell":     shell,
		"root":      os.Geteuid() == 0,
	})

	go m.readOutput(session)
	go m.watchSession(session)
	return nil
}

func (m *TerminalManager) Input(request terminalInputRequest) error {
	session := m.get(request.SessionID)
	if session == nil {
		return errors.New("terminal session not found")
	}
	data, err := base64.StdEncoding.DecodeString(request.Data)
	if err != nil || len(data) > maxTerminalInput {
		return errors.New("invalid terminal input")
	}
	session.touch()
	_, err = session.pty.Write(data)
	return err
}

func (m *TerminalManager) Resize(request terminalResizeRequest) error {
	session := m.get(request.SessionID)
	if session == nil {
		return errors.New("terminal session not found")
	}
	cols, rows := normalizeTerminalSize(request.Cols, request.Rows)
	session.touch()
	return pty.Setsize(session.pty, &pty.Winsize{Cols: cols, Rows: rows})
}

func (m *TerminalManager) Close(request terminalCloseRequest) {
	reason := request.Reason
	if reason == "" {
		reason = "terminal session closed"
	}
	m.finish(m.get(request.SessionID), reason)
}

func (m *TerminalManager) CloseAll(reason string) {
	m.mu.Lock()
	sessions := make([]*terminalSession, 0, len(m.sessions))
	for _, session := range m.sessions {
		sessions = append(sessions, session)
	}
	m.mu.Unlock()
	for _, session := range sessions {
		m.finish(session, reason)
	}
}

func (m *TerminalManager) readOutput(session *terminalSession) {
	buffer := make([]byte, 16*1024)
	for {
		n, err := session.pty.Read(buffer)
		if n > 0 {
			session.touch()
			m.send("TerminalOutput", map[string]interface{}{
				"sessionId": session.id,
				"data":      base64.StdEncoding.EncodeToString(buffer[:n]),
			})
		}
		if err != nil {
			if !errors.Is(err, io.EOF) {
				m.finish(session, "terminal stream closed")
			} else {
				m.finish(session, "shell exited")
			}
			return
		}
	}
}

func (m *TerminalManager) watchSession(session *terminalSession) {
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		if m.get(session.id) == nil {
			return
		}
		session.mu.Lock()
		idle := time.Since(session.lastActivity)
		duration := time.Since(session.startedAt)
		session.mu.Unlock()
		if idle > terminalIdleTimeout {
			m.finish(session, "terminal idle timeout")
			return
		}
		if duration > terminalMaxDuration {
			m.finish(session, "terminal maximum duration reached")
			return
		}
	}
}

func (m *TerminalManager) finish(session *terminalSession, reason string) {
	if session == nil {
		return
	}
	session.closeOnce.Do(func() {
		m.mu.Lock()
		delete(m.sessions, session.id)
		m.mu.Unlock()
		_ = session.pty.Close()
		if session.command.Process != nil {
			_ = session.command.Process.Kill()
			_, _ = session.command.Process.Wait()
		}
		m.send("TerminalClosed", map[string]interface{}{
			"sessionId": session.id,
			"reason":    reason,
		})
	})
}

func (m *TerminalManager) get(sessionID string) *terminalSession {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.sessions[sessionID]
}

func (session *terminalSession) touch() {
	session.mu.Lock()
	session.lastActivity = time.Now()
	session.mu.Unlock()
}

func detectShell() (string, error) {
	for _, candidate := range []string{"/bin/bash", "/bin/ash", "/bin/sh"} {
		if info, err := os.Stat(candidate); err == nil && !info.IsDir() {
			return candidate, nil
		}
	}
	return "", errors.New("no supported shell found")
}

func validTerminalSessionID(value string) bool {
	if len(value) < 16 || len(value) > 64 {
		return false
	}
	for _, char := range value {
		if !((char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') || (char >= '0' && char <= '9') || char == '-') {
			return false
		}
	}
	return true
}

func normalizeTerminalSize(cols, rows uint16) (uint16, uint16) {
	if cols < 20 {
		cols = 120
	}
	if cols > 400 {
		cols = 400
	}
	if rows < 5 {
		rows = 32
	}
	if rows > 160 {
		rows = 160
	}
	return cols, rows
}
