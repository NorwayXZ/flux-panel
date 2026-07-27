package socket

import (
	"errors"
	"strings"

	"github.com/go-gost/x/config"
	parser "github.com/go-gost/x/config/parsing/admission"
	"github.com/go-gost/x/registry"
)

type createAdmissionRequest struct {
	Data config.AdmissionConfig `json:"data"`
}

type deleteAdmissionRequest struct {
	Admission string `json:"admission"`
}

func createAdmission(req createAdmissionRequest) error {
	name := strings.TrimSpace(req.Data.Name)
	if name == "" {
		return errors.New("admission name is required")
	}
	req.Data.Name = name
	if registry.AdmissionRegistry().IsRegistered(name) {
		return errors.New("admission " + name + " already exists")
	}
	if err := registry.AdmissionRegistry().Register(name, parser.ParseAdmission(&req.Data)); err != nil {
		return errors.New("admission " + name + " already exists")
	}
	config.OnUpdate(func(c *config.Config) error {
		c.Admissions = append(c.Admissions, &req.Data)
		return nil
	})
	return nil
}

func deleteAdmission(req deleteAdmissionRequest) error {
	name := strings.TrimSpace(req.Admission)
	if name == "" {
		return errors.New("admission name is required")
	}
	if !registry.AdmissionRegistry().IsRegistered(name) {
		return errors.New("admission " + name + " not found")
	}
	registry.AdmissionRegistry().Unregister(name)
	config.OnUpdate(func(c *config.Config) error {
		items := c.Admissions
		c.Admissions = nil
		for _, item := range items {
			if item.Name != name {
				c.Admissions = append(c.Admissions, item)
			}
		}
		return nil
	})
	return nil
}
