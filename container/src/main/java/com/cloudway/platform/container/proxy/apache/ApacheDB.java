/**
 * Cloudway Platform
 * Copyright (c) 2012-2013 Cloudway Technology, Inc.
 * All rights reserved.
 */

package com.cloudway.platform.container.proxy.apache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static java.nio.file.StandardCopyOption.*;

import com.cloudway.platform.common.os.Config;
import com.cloudway.platform.common.os.Exec;
import com.cloudway.platform.common.io.IO;

import static com.cloudway.platform.common.util.MoreCollectors.*;
import static com.cloudway.platform.common.io.MoreFiles.*;

class ApacheDB
{
    private static final Path   BASEDIR = Config.VAR_DIR.resolve(".httpd");
    private static final String SUFFIX = ".txt";

    private static final String httxt2dbm =
        Stream.of("/usr/bin", "/usr/sbin", "/bin", "/sbin")
              .map(d -> Paths.get(d, "httxt2dbm"))
              .filter(Files::exists)
              .map(Path::toString)
              .findFirst()
              .orElse("httxt2dbm");

    private final String mapname;
    private final boolean nodbm;

    private Map<String, String> map = new LinkedHashMap<>();
    private long mtime = -1;

    public ApacheDB(String mapname, boolean nodbm) {
        this.mapname = mapname;
        this.nodbm = nodbm;
    }

    public ApacheDB(String mapname) {
        this(mapname, false);
    }

    public synchronized void reading(Consumer<Map<String,String>> action)
        throws IOException
    {
        action.accept(load());
    }

    public synchronized boolean writting(Consumer<Map<String,String>> action)
        throws IOException
    {
        Map<String, String> newmap = new LinkedHashMap<>(load());
        action.accept(newmap);

        if (map.equals(newmap)) {
            return false;
        } else {
            map = newmap;
            store(newmap);
            return true;
        }
    }

    private Map<String, String> load() throws IOException {
        Path file = BASEDIR.resolve(mapname + SUFFIX);
        if (Files.exists(file)) {
            long last_mtime = Files.getLastModifiedTime(file).to(TimeUnit.NANOSECONDS);
            if (last_mtime != this.mtime) {
                try (Stream<String> lines = Files.lines(file)) {
                    this.map = lines.collect(toSplittingMap(' '));
                    this.mtime = last_mtime;
                }
            }
        }
        return map;
    }

    private void store(Map<String, String> map) throws IOException {
        if (!Files.exists(BASEDIR)) {
            mkdir(BASEDIR);
        }

        Path txt = BASEDIR.resolve(mapname + SUFFIX);
        try (BufferedWriter f = Files.newBufferedWriter(txt)) {
            IO.forEach(map, (k,v) -> f.write(k + " " + v + "\n"));
        }

        if (!nodbm) {
            Path dbm = BASEDIR.resolve(mapname + ".db");
            Path wd = Files.createTempDirectory(BASEDIR, mapname + ".db~");
            try {
                Path tmpdb = wd.resolve("tmp.db");
                Exec.args(httxt2dbm, "-f", "DB", "-i", txt, "-o", tmpdb)
                    .silentIO().checkError().run();
                Files.move(tmpdb, dbm, REPLACE_EXISTING, ATOMIC_MOVE);
            } finally {
                deleteFileTree(wd);
            }
        }
    }
}
