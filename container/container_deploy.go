package container

import (
	"archive/tar"
	"compress/gzip"
	"fmt"
	"io"
	"io/ioutil"
	"math/rand"
	"os"
	"path/filepath"

	"golang.org/x/net/context"
	"gopkg.in/yaml.v2"

	"github.com/cloudway/platform/hub"
	"github.com/cloudway/platform/pkg/archive"
	"github.com/cloudway/platform/pkg/manifest"
	"github.com/cloudway/platform/pkg/serverlog"
	"github.com/docker/engine-api/types"
)

func (c *Container) Deploy(ctx context.Context, path string) error {
	// Create context archive containing the repo archive
	r, w := io.Pipe()
	go func() {
		tw := tar.NewWriter(w)
		err := archive.CopyFileTree(tw, "", path, nil, false)
		tw.Close()
		w.CloseWithError(err)
	}()

	// Copy file to container
	err := c.CopyToContainer(ctx, c.ID, c.DeployDir(), r, types.CopyToContainerOptions{})
	if err != nil {
		return err
	}

	// Send signal to container to complete the deployment
	c.ContainerKill(ctx, c.ID, "SIGHUP")
	return nil
}

func PrepareRepo(content io.Reader, zip bool) (repodir string, err error) {
	// create a temporary directory to hold deployment archive
	repodir, err = ioutil.TempDir("", "deploy")
	if err != nil {
		return "", err
	}

	// save archive to a temporary file
	filename := filepath.Join(repodir, filepath.Base(repodir)+".tar.gz")
	repofile, err := os.Create(filename)
	if err != nil {
		return
	}
	defer repofile.Close()

	if zip {
		w := gzip.NewWriter(repofile)
		_, err = io.Copy(w, content)
		if err == nil {
			err = w.Close()
		}
	} else {
		_, err = io.Copy(repofile, content)
	}
	return
}

func (cli DockerClient) DistributeRepo(ctx context.Context, containers []*Container, repo io.Reader, zip bool) error {
	repodir, err := PrepareRepo(repo, zip)
	if repodir != "" {
		defer os.RemoveAll(repodir)
	}
	if err != nil {
		return err
	}

	for _, c := range containers {
		if c.Category().IsFramework() {
			er := c.Deploy(ctx, repodir)
			if er != nil {
				err = er
			}
		}
	}
	return err
}

func (cli DockerClient) DeployRepo(ctx context.Context, name, namespace string, in io.Reader, log *serverlog.ServerLog) error {
	containers, err := cli.FindApplications(ctx, name, namespace)
	if err != nil {
		return err
	}
	if len(containers) == 0 {
		return fmt.Errorf("%s: application not found", name)
	}

	// randomly select a base container
	var base *Container
	if len(containers) == 1 {
		base = containers[0]
	} else {
		base = containers[rand.Intn(len(containers))]
	}

	if base.Flags()&HotDeployable != 0 {
		// distribute the repository directly
		return cli.DistributeRepo(ctx, containers, in, false)
	} else {
		// build and distribute the repository
		return build(cli, ctx, containers, base, in, log)
	}
}

func build(cli DockerClient, ctx context.Context, containers []*Container, base *Container, in io.Reader, log *serverlog.ServerLog) (err error) {
	plugin, err := readPluginManifestFromContainer(ctx, base)
	if err != nil {
		return
	}

	// create a builder container
	opts := CreateOptions{
		Name:      base.Name,
		Namespace: base.Namespace,
		Plugin:    plugin,
		Image:     base.Config.Image,
		Home:      base.Home(),
		User:      base.User(),
		Log:       log,
	}
	builder, err := cli.CreateBuilder(ctx, opts)
	if err != nil {
		return
	}
	defer func() {
		rmopts := types.ContainerRemoveOptions{Force: true, RemoveVolumes: true}
		cli.ContainerRemove(ctx, builder.ID, rmopts)
	}()

	// start builder container
	err = builder.ContainerStart(ctx, builder.ID, types.ContainerStartOptions{})
	if err != nil {
		return
	}

	// build the application, use cache during build
	copyCache(ctx, plugin, base, builder, true)
	err = builder.Exec(ctx, "", in, log.Stdout(), log.Stderr(), "/usr/bin/cwctl", "build")
	if err != nil {
		return
	}
	copyCache(ctx, plugin, builder, base, false)

	// download application repository from builder container
	repo, _, err := builder.CopyFromContainer(ctx, builder.ID, builder.RepoDir()+"/.")
	if err != nil {
		return
	}
	defer repo.Close()

	return cli.DistributeRepo(ctx, containers, repo, true)
}

func readPluginManifestFromContainer(ctx context.Context, base *Container) (meta *manifest.Plugin, err error) {
	_, _, pn, _, _ := hub.ParseTag(base.PluginTag())
	path := fmt.Sprintf("%s/%s/manifest/plugin.yml", base.Home(), pn)
	r, _, err := base.CopyFromContainer(ctx, base.ID, path)
	if err != nil {
		return
	}
	defer r.Close()

	var content []byte
	tr := tar.NewReader(r)
	if _, err = tr.Next(); err != nil {
		return
	}
	if content, err = ioutil.ReadAll(tr); err != nil {
		return
	}

	var plugin manifest.Plugin
	err = yaml.Unmarshal(content, &plugin)
	if err != nil {
		return
	}

	plugin.Tag = base.PluginTag()
	return &plugin, err
}

func copyCache(ctx context.Context, plugin *manifest.Plugin, from, to *Container, chown bool) {
	if len(plugin.BuildCache) == 0 {
		return
	}

	var paths = make([]string, len(plugin.BuildCache))
	for i, cache := range plugin.BuildCache {
		paths[i] = from.Home() + "/" + cache
	}

	opts := types.CopyToContainerOptions{AllowOverwriteDirWithFile: true}
	for _, path := range paths {
		content, _, err := from.CopyFromContainer(ctx, from.ID, path+"/.")
		if err == nil {
			to.CopyToContainer(ctx, to.ID, path+"/", content, opts)
			content.Close()
		}
	}

	if chown {
		args := append([]string{"chown", "-R", to.User()}, paths...)
		to.Exec(ctx, "root", nil, nil, nil, args...)
	}
}
