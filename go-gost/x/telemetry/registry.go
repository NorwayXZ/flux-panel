package telemetry

import (
	"net"
	"sort"
	"strings"
	"sync"
	"time"
)

const maxSamples = 20

type Sample struct {
	Value    string `json:"v"`
	Kind     string `json:"k,omitempty"`
	Count    uint64 `json:"c"`
	LastSeen int64  `json:"l"`
}

type serviceSamples struct {
	sources map[string]*Sample
	hosts   map[string]*Sample
}

var registry = struct {
	sync.Mutex
	services map[string]*serviceSamples
}{services: make(map[string]*serviceSamples)}

func ObserveSource(service, source, kind string) {
	service = strings.TrimSpace(service)
	source = normalizeAddress(source)
	if service == "" || source == "" {
		return
	}
	if kind == "" {
		kind = "previous_hop"
	}
	observe(service, source, kind, true)
}

func ObserveHost(service, host string) {
	service = strings.TrimSpace(service)
	host = normalizeHost(host)
	if service == "" || host == "" {
		return
	}
	observe(service, host, "", false)
}

func Snapshot(service string) (sources, hosts []Sample) {
	registry.Lock()
	defer registry.Unlock()
	entry := registry.services[service]
	if entry == nil {
		return nil, nil
	}
	return snapshot(entry.sources), snapshot(entry.hosts)
}

func ResetForTest() {
	registry.Lock()
	defer registry.Unlock()
	registry.services = make(map[string]*serviceSamples)
}

func observe(service, value, kind string, source bool) {
	now := time.Now().UnixMilli()
	registry.Lock()
	defer registry.Unlock()
	entry := registry.services[service]
	if entry == nil {
		entry = &serviceSamples{sources: make(map[string]*Sample), hosts: make(map[string]*Sample)}
		registry.services[service] = entry
	}
	target := entry.hosts
	key := value
	if source {
		target = entry.sources
		key = kind + "\x00" + value
	}
	if sample := target[key]; sample != nil {
		sample.Count++
		sample.LastSeen = now
		return
	}
	if len(target) >= maxSamples {
		oldestKey := ""
		oldestAt := int64(^uint64(0) >> 1)
		for existingKey, sample := range target {
			if sample.LastSeen < oldestAt {
				oldestKey = existingKey
				oldestAt = sample.LastSeen
			}
		}
		delete(target, oldestKey)
	}
	target[key] = &Sample{Value: value, Kind: kind, Count: 1, LastSeen: now}
}

func snapshot(values map[string]*Sample) []Sample {
	result := make([]Sample, 0, len(values))
	for _, value := range values {
		result = append(result, *value)
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].Count != result[j].Count {
			return result[i].Count > result[j].Count
		}
		return result[i].LastSeen > result[j].LastSeen
	})
	return result
}

func normalizeAddress(value string) string {
	value = strings.TrimSpace(value)
	if host, _, err := net.SplitHostPort(value); err == nil {
		return strings.Trim(host, "[]")
	}
	return strings.Trim(value, "[]")
}

func normalizeHost(value string) string {
	value = strings.TrimSpace(strings.ToLower(value))
	if host, _, err := net.SplitHostPort(value); err == nil {
		return strings.TrimSuffix(strings.Trim(host, "[]"), ".")
	}
	return strings.TrimSuffix(strings.Trim(value, "[]"), ".")
}
