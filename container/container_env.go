package container

import (
	"archive/tar"
	"bytes"
	"errors"
	"io/ioutil"
	"regexp"
	"strconv"
	"strings"

	"github.com/docker/engine-api/types"
	"golang.org/x/net/context"

	"github.com/cloudway/platform/pkg/manifest"
)

// Adds the variable to the environment with the value, if name does not
// exists. If name does exist in the environment, then its value is changed
// to value.
func (c *Container) Setenv(ctx context.Context, name, value string) error {
	content := []byte(value)

	// Make an archive containing the environmnet file
	buf := &bytes.Buffer{}
	tw := tar.NewWriter(buf)
	tw.WriteHeader(&tar.Header{
		Name: name,
		Mode: 0644,
		Size: int64(len(content)),
	})
	tw.Write(content)
	tw.Close()

	// Copy the archive to the container at specified path
	return c.CopyToContainer(ctx, c.ID, c.EnvDir(), buf, types.CopyToContainerOptions{})
}

// Get an environment variable value.
func (c *Container) Getenv(ctx context.Context, name string) (string, error) {
	// Copy the archive from the container that contains the environment file
	path := c.EnvDir() + "/" + name
	r, _, err := c.CopyFromContainer(ctx, c.ID, path)
	if err != nil {
		return "", err
	}
	defer r.Close()

	// Read archive to get environment value
	var content []byte
	tr := tar.NewReader(r)
	if _, err = tr.Next(); err != nil {
		return "", err
	}
	if content, err = ioutil.ReadAll(tr); err != nil {
		return "", err
	}
	return strings.TrimRight(string(content), "\r\n"), nil
}

func (c *Container) ActiveState(ctx context.Context) manifest.ActiveState {
	// Get active state from running processes
	if c.State.Running {
		state, err := c.activeStateFromRunningProcess(ctx)
		if err == nil {
			return state
		}
	} else {
		return manifest.StateStopped
	}

	// Fallback to get active state from state file
	str, err := c.Getenv(ctx, ".state")
	if err != nil {
		return manifest.StateUnknown
	}
	i, err := strconv.Atoi(str)
	if err != nil {
		return manifest.StateUnknown
	}
	return manifest.ActiveState(i)
}

var statePattern = regexp.MustCompile(`^/usr/bin/cwctl \[([0-9])\]`)

func (c *Container) activeStateFromRunningProcess(ctx context.Context) (manifest.ActiveState, error) {
	ps, err := c.ContainerTop(ctx, c.ID, []string{})
	if err != nil {
		return manifest.StateUnknown, err
	}

	var cmdIndex = -1
	for i, t := range ps.Titles {
		if strings.ToUpper(t) == "COMMAND" {
			cmdIndex = i
			break
		}
	}
	if cmdIndex == -1 {
		return manifest.StateUnknown, errors.New("no command column in process list")
	}

	for _, p := range ps.Processes {
		command := p[cmdIndex]
		if m := statePattern.FindStringSubmatch(command); m != nil {
			state, err := strconv.Atoi(command)
			return manifest.ActiveState(state), err
		}
	}
	return manifest.StateUnknown, errors.New("sandbox process not found")
}
