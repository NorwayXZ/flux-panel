package socket

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

type dockerAppInspectRequest struct {
	Limit int `json:"limit"`
}

type dockerAppPort struct {
	PrivatePort int    `json:"privatePort"`
	PublicPort  int    `json:"publicPort,omitempty"`
	Type        string `json:"type"`
	IP          string `json:"ip,omitempty"`
}

type dockerAppContainer struct {
	ID     string          `json:"id"`
	Name   string          `json:"name"`
	Image  string          `json:"image"`
	State  string          `json:"state"`
	Status string          `json:"status"`
	Ports  []dockerAppPort `json:"ports,omitempty"`
}

type dockerAppInspectResponse struct {
	DockerAvailable  bool                 `json:"dockerAvailable"`
	DockerVersion    string               `json:"dockerVersion,omitempty"`
	ComposeAvailable bool                 `json:"composeAvailable"`
	Containers       []dockerAppContainer `json:"containers"`
	Error            string               `json:"error,omitempty"`
}

type dockerAppRequest struct {
	AppID         int64             `json:"appId"`
	Action        string            `json:"action"`
	TemplateID    string            `json:"templateId"`
	Name          string            `json:"name"`
	ContainerName string            `json:"containerName"`
	Image         string            `json:"image"`
	HostPort      int               `json:"hostPort"`
	ContainerPort int               `json:"containerPort"`
	VolumeName    string            `json:"volumeName"`
	Env           map[string]string `json:"env"`
}

type dockerAppResponse struct {
	ComposePath string `json:"composePath,omitempty"`
	BackupPath  string `json:"backupPath,omitempty"`
	Output      string `json:"output,omitempty"`
}

var dockerAppSafeName = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_.-]{1,80}$`)

func (w *WebSocketReporter) handleDockerAppInspect(data interface{}) (dockerAppInspectResponse, error) {
	request := dockerAppInspectRequest{Limit: 120}
	if err := decodeCommandData(data, &request); err != nil {
		return dockerAppInspectResponse{}, err
	}
	if request.Limit <= 0 || request.Limit > 500 {
		request.Limit = 120
	}
	version, err := dockerCommand(8*time.Second, "version", "--format", "{{.Server.Version}}")
	if err != nil {
		return dockerAppInspectResponse{DockerAvailable: false, Containers: []dockerAppContainer{}, Error: cleanDockerOutput(err.Error())}, nil
	}
	composeAvailable := dockerComposeAvailable()
	output, err := dockerCommand(12*time.Second, "ps", "--format", "{{json .}}")
	if err != nil {
		return dockerAppInspectResponse{}, err
	}
	containers := parseDockerContainers(output, request.Limit)
	return dockerAppInspectResponse{
		DockerAvailable: true, DockerVersion: strings.TrimSpace(version),
		ComposeAvailable: composeAvailable, Containers: containers,
	}, nil
}

func (w *WebSocketReporter) handleDockerAppDeploy(data interface{}) (dockerAppResponse, error) {
	var request dockerAppRequest
	if err := decodeCommandData(data, &request); err != nil {
		return dockerAppResponse{}, err
	}
	if err := validateDockerAppRequest(request); err != nil {
		return dockerAppResponse{}, err
	}
	return deployDockerApp(request)
}

func (w *WebSocketReporter) handleDockerAppAction(data interface{}) (dockerAppResponse, error) {
	var request dockerAppRequest
	if err := decodeCommandData(data, &request); err != nil {
		return dockerAppResponse{}, err
	}
	if err := validateDockerAppRequest(request); err != nil {
		return dockerAppResponse{}, err
	}
	switch strings.ToLower(request.Action) {
	case "upgrade":
		return deployDockerApp(request)
	case "backup":
		return backupDockerApp(request)
	case "stop":
		output, err := dockerCommand(30*time.Second, "stop", request.ContainerName)
		return dockerAppResponse{Output: output}, err
	case "start":
		output, err := dockerCommand(30*time.Second, "start", request.ContainerName)
		return dockerAppResponse{Output: output}, err
	case "remove", "rollback":
		output, err := dockerCommand(45*time.Second, "rm", "-f", request.ContainerName)
		return dockerAppResponse{Output: output}, err
	default:
		return dockerAppResponse{}, errors.New("unsupported docker app action")
	}
}

func deployDockerApp(request dockerAppRequest) (dockerAppResponse, error) {
	if _, err := dockerCommand(10*time.Second, "version", "--format", "{{.Server.Version}}"); err != nil {
		return dockerAppResponse{}, fmt.Errorf("docker unavailable: %w", err)
	}
	root := filepath.Join("/etc/cloudnest/docker-apps", request.ContainerName)
	if err := os.MkdirAll(root, 0o755); err != nil {
		return dockerAppResponse{}, err
	}
	composePath := filepath.Join(root, "docker-compose.yml")
	compose := dockerAppCompose(request)
	if err := os.WriteFile(composePath, []byte(compose), 0o600); err != nil {
		return dockerAppResponse{}, err
	}
	_, _ = dockerCommand(20*time.Second, "pull", request.Image)
	if dockerComposeAvailable() {
		output, err := dockerComposeCommand(90*time.Second, "-f", composePath, "up", "-d")
		return dockerAppResponse{ComposePath: composePath, Output: output}, err
	}
	_, _ = dockerCommand(20*time.Second, "rm", "-f", request.ContainerName)
	args := []string{"run", "-d", "--restart", "unless-stopped", "--name", request.ContainerName,
		"-p", strconv.Itoa(request.HostPort) + ":" + strconv.Itoa(request.ContainerPort),
		"-v", request.VolumeName + ":/data"}
	for key, value := range request.Env {
		args = append(args, "-e", key+"="+value)
	}
	args = append(args, request.Image)
	output, err := dockerCommand(90*time.Second, args...)
	return dockerAppResponse{ComposePath: composePath, Output: output}, err
}

func backupDockerApp(request dockerAppRequest) (dockerAppResponse, error) {
	backupDir := "/etc/cloudnest/docker-apps/backups"
	if err := os.MkdirAll(backupDir, 0o700); err != nil {
		return dockerAppResponse{}, err
	}
	backupPath := filepath.Join(backupDir, fmt.Sprintf("%s-%s.tar.gz", request.ContainerName, time.Now().Format("20060102150405")))
	output, err := dockerCommand(90*time.Second, "run", "--rm", "-v", request.VolumeName+":/data:ro",
		"-v", backupDir+":/backup", "alpine", "tar", "czf", "/backup/"+filepath.Base(backupPath), "-C", "/data", ".")
	return dockerAppResponse{BackupPath: backupPath, Output: output}, err
}

func validateDockerAppRequest(request dockerAppRequest) error {
	if !dockerAppSafeName.MatchString(request.ContainerName) {
		return errors.New("invalid container name")
	}
	if strings.TrimSpace(request.Image) == "" || strings.ContainsAny(request.Image, " \n\r\t;&|") {
		return errors.New("invalid docker image")
	}
	if request.HostPort < 1 || request.HostPort > 65535 || request.ContainerPort < 1 || request.ContainerPort > 65535 {
		return errors.New("invalid docker port")
	}
	if strings.TrimSpace(request.VolumeName) == "" {
		return errors.New("invalid volume name")
	}
	for key := range request.Env {
		if !regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_]*$`).MatchString(key) {
			return errors.New("invalid env key")
		}
	}
	return nil
}

func dockerAppCompose(request dockerAppRequest) string {
	var builder strings.Builder
	builder.WriteString("services:\n")
	builder.WriteString("  app:\n")
	builder.WriteString("    image: " + yamlQuote(request.Image) + "\n")
	builder.WriteString("    container_name: " + yamlQuote(request.ContainerName) + "\n")
	builder.WriteString("    restart: unless-stopped\n")
	builder.WriteString("    ports:\n")
	builder.WriteString("      - " + yamlQuote(fmt.Sprintf("%d:%d", request.HostPort, request.ContainerPort)) + "\n")
	builder.WriteString("    volumes:\n")
	builder.WriteString("      - " + yamlQuote(request.VolumeName+":/data") + "\n")
	if len(request.Env) > 0 {
		builder.WriteString("    environment:\n")
		keys := make([]string, 0, len(request.Env))
		for key := range request.Env {
			keys = append(keys, key)
		}
		sort.Strings(keys)
		for _, key := range keys {
			builder.WriteString("      " + key + ": " + yamlQuote(request.Env[key]) + "\n")
		}
	}
	builder.WriteString("volumes:\n")
	builder.WriteString("  " + request.VolumeName + ":\n")
	return builder.String()
}

func dockerCommand(timeout time.Duration, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	command := exec.CommandContext(ctx, "docker", args...)
	output, err := command.CombinedOutput()
	if ctx.Err() == context.DeadlineExceeded {
		return string(output), errors.New("docker command timed out")
	}
	if err != nil {
		return string(output), fmt.Errorf("%w: %s", err, cleanDockerOutput(string(output)))
	}
	return string(output), nil
}

func dockerComposeCommand(timeout time.Duration, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	commandArgs := append([]string{"compose"}, args...)
	command := exec.CommandContext(ctx, "docker", commandArgs...)
	output, err := command.CombinedOutput()
	if ctx.Err() == context.DeadlineExceeded {
		return string(output), errors.New("docker compose command timed out")
	}
	if err != nil {
		return string(output), fmt.Errorf("%w: %s", err, cleanDockerOutput(string(output)))
	}
	return string(output), nil
}

func dockerComposeAvailable() bool {
	_, err := dockerComposeCommand(8*time.Second, "version")
	return err == nil
}

func parseDockerContainers(output string, limit int) []dockerAppContainer {
	lines := strings.Split(output, "\n")
	containers := make([]dockerAppContainer, 0)
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		var raw struct {
			ID     string `json:"ID"`
			Names  string `json:"Names"`
			Image  string `json:"Image"`
			State  string `json:"State"`
			Status string `json:"Status"`
			Ports  string `json:"Ports"`
		}
		if err := json.Unmarshal([]byte(line), &raw); err != nil {
			continue
		}
		containers = append(containers, dockerAppContainer{
			ID: raw.ID, Name: raw.Names, Image: raw.Image, State: raw.State, Status: raw.Status,
		})
		if len(containers) >= limit {
			break
		}
	}
	return containers
}

func yamlQuote(value string) string {
	escaped := strings.ReplaceAll(value, "\\", "\\\\")
	escaped = strings.ReplaceAll(escaped, "\"", "\\\"")
	return "\"" + escaped + "\""
}

func cleanDockerOutput(value string) string {
	value = strings.TrimSpace(value)
	if len(value) > 500 {
		return value[:500]
	}
	return value
}
