//
// $Id$
//
// Getdown - application installer, patcher and launcher
// Copyright (C) 2004-2010 Three Rings Design, Inc.
//
// Redistribution and use in source and binary forms, with or without modification, are permitted
// provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice, this list of
//    conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice, this list of
//    conditions and the following disclaimer in the documentation and/or other materials provided
//    with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES,
// INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
// PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT,
// INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
// TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
package com.threerings.getdown.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.net.URL;
import java.security.MessageDigest;

import java.util.Comparator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import com.samskivert.io.StreamUtil;
import com.samskivert.util.CollectionUtil;
import com.samskivert.util.FileUtil;
import com.samskivert.util.SortableArrayList;
import com.samskivert.util.StringUtil;

import com.threerings.getdown.util.ProgressObserver;

import static com.threerings.getdown.Log.log;

/**
 * Models a single file resource used by an {@link Application}.
 */
public class Resource
{
    /**
     * Creates a resource with the supplied remote URL and local path.
     */
    public Resource (String path, URL remote, File local, boolean unpack)
    {
        _path = path;
        _remote = remote;
        _local = local;
        _marker = new File(_local.getPath() + "v");
        _unpack = unpack;
    }

    /**
     * Returns the path associated with this resource.
     */
    public String getPath ()
    {
        return _path;
    }

    /**
     * Returns the local location of this resource.
     */
    public File getLocal ()
    {
        return _local;
    }

    /**
     * Returns the remote location of this resource.
     */
    public URL getRemote ()
    {
        return _remote;
    }

    /**
     * Returns true if this resource should be unpacked as a part of the
     * validation process.
     */
    public boolean shouldUnpack ()
    {
        return _unpack;
    }

    /**
     * Computes the MD5 hash of this resource's underlying file.
     * <em>Note:</em> This is both CPU and I/O intensive.
     */
    public String computeDigest (MessageDigest md, ProgressObserver obs)
        throws IOException
    {
        return computeDigest(_local, md, obs);
    }

    /**
     * Returns true if this resource has an associated "validated" marker
     * file.
     */
    public boolean isMarkedValid ()
    {
        if (!_local.exists()) {
            clearMarker();
            return false;
        }
        return _marker.exists();
    }

    /**
     * Creates a "validated" marker file for this resource to indicate
     * that its MD5 hash has been computed and compared with the value in
     * the digest file.
     *
     * @throws IOException if we fail to create the marker file.
     */
    public void markAsValid ()
        throws IOException
    {
        _marker.createNewFile();
    }

    /**
     * Removes any "validated" marker file associated with this resource.
     */
    public void clearMarker ()
    {
        if (_marker.exists()) {
            if (!_marker.delete()) {
                log.warning("Failed to erase marker file '" + _marker + "'.");
            }
        }
    }

    /**
     * Unpacks this resource file into the directory that contains it. Returns
     * false if an error occurs while unpacking it.
     */
    public boolean unpack ()
    {
        // sanity check
        if (!_local.getPath().endsWith(".jar")) {
            log.warning("Requested to unpack non-jar file '" + _local + "'.");
            return false;
        }
        try {
            return FileUtil.unpackJar(new JarFile(_local), _local.getParentFile());
        } catch (IOException ioe) {
            log.warning("Failed to create JarFile from '" + _local + "': " + ioe);
            return false;
        }
    }

    /**
     * Wipes this resource file along with any "validated" marker file
     * that may be associated with it.
     */
    public void erase ()
    {
        clearMarker();
        if (_local.exists()) {
            if (!_local.delete()) {
                log.warning("Failed to erase resource '" + _local + "'.");
            }
        }
    }

    /**
     * If our path is equal, we are equal.
     */
    @Override
    public boolean equals (Object other)
    {
        if (other instanceof Resource) {
            return _path.equals(((Resource)other)._path);
        } else {
            return false;
        }
    }

    /**
     * We hash on our path.
     */
    @Override
    public int hashCode ()
    {
        return _path.hashCode();
    }

    /**
     * Returns a string representation of this instance.
     */
    @Override
    public String toString ()
    {
        return _path;
    }

    /**
     * Computes the MD5 hash of the supplied file.
     */
    public static String computeDigest (
        File target, MessageDigest md, ProgressObserver obs)
        throws IOException
    {
        md.reset();
        byte[] buffer = new byte[DIGEST_BUFFER_SIZE];
        int read;

        // if this is a jar file, we need to compute the digest in a
        // timestamp and file order agnostic manner to properly correlate
        // jardiff patched jars with their unpatched originals
        if (target.getPath().endsWith(".jar")) {
            JarFile jar = new JarFile(target);
            try {
                SortableArrayList<JarEntry> entries =
                    new SortableArrayList<JarEntry>();
                CollectionUtil.addAll(entries, jar.entries());
                entries.sort(ENTRY_COMP);

                int eidx = 0;
                for (JarEntry entry : entries) {
                    // skip metadata; we just want the goods
                    if (entry.getName().startsWith("META-INF")) {
                        updateProgress(obs, eidx, entries.size());
                        continue;
                    }

                    // add this file's data to the MD5 hash
                    InputStream in = null;
                    try {
                        in = jar.getInputStream(entry);
                        while ((read = in.read(buffer)) != -1) {
                            md.update(buffer, 0, read);
                        }
                    } finally {
                        StreamUtil.close(in);
                    }
                    updateProgress(obs, eidx, entries.size());
                }

            } finally {
                try {
                    jar.close();
                } catch (IOException ioe) {
                    log.warning("Error closing jar [path=" + target + ", error=" + ioe + "].");
                }
            }

        } else {
            long totalSize = target.length(), position = 0L;
            FileInputStream fin = null;
            try {
                fin = new FileInputStream(target);
                while ((read = fin.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                    position += read;
                    updateProgress(obs, position, totalSize);
                }
            } finally {
                StreamUtil.close(fin);
            }
        }
        return StringUtil.hexlate(md.digest());
    }

    /** Helper function to simplify the process of reporting progress. */
    protected static void updateProgress (
        ProgressObserver obs, long pos, long total)
    {
        if (obs != null) {
            obs.progress((int)(100 * pos / total));
        }
    }

    protected String _path;
    protected URL _remote;
    protected File _local, _marker;
    protected boolean _unpack;

    /** Used to sort the entries in a jar file. */
    protected static final Comparator<JarEntry> ENTRY_COMP =
            new Comparator<JarEntry>() {
        public int compare (JarEntry e1, JarEntry e2) {
            return e1.getName().compareTo(e2.getName());
        }
    };

    protected static final int DIGEST_BUFFER_SIZE = 5 * 1025;
}
