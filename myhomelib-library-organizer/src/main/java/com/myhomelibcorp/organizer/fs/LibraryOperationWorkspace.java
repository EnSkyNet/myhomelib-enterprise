package com.myhomelibcorp.organizer.fs;

import com.myhomelibcorp.shared.util.AppPaths;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;

/**
 * Organizer staging workspace. An unfinished workspace is intentionally preserved for crash recovery.
 * Call {@link #cleanup()} only after the operation has been durably marked completed or rolled back.
 */
public final class LibraryOperationWorkspace implements AutoCloseable {
    private final Path root;
    private LibraryOperationWorkspace(Path root){this.root=root;}

    public static LibraryOperationWorkspace create(UUID operationId) throws IOException {
        Path root=rootFor(operationId);
        OrganizerPathSafety.createDirectoriesNoLinks(root.resolve("input"), "Organizer workspace");
        OrganizerPathSafety.createDirectoriesNoLinks(root.resolve("output"), "Organizer workspace");
        OrganizerPathSafety.createDirectoriesNoLinks(root.resolve("backup"), "Organizer workspace");
        OrganizerPathSafety.createDirectoriesNoLinks(root.resolve("logs"), "Organizer workspace");
        return new LibraryOperationWorkspace(root);
    }

    public static Path baseRoot() {
        return AppPaths.cacheDir().resolve("organizer-workspaces").toAbsolutePath().normalize();
    }

    public static Path rootFor(UUID operationId) {
        return baseRoot().resolve(operationId.toString()).toAbsolutePath().normalize();
    }

    public static Path manifestFor(UUID operationId) { return rootFor(operationId).resolve("manifest.json"); }

    public Path manifestPath(){return root.resolve("manifest.json");}
    public Path input(UUID itemId,String extension){
        String ext=extension==null?"":extension.replaceAll("[^A-Za-z0-9._-]","");
        return root.resolve("input").resolve(itemId+ext);
    }
    public Path staged(UUID itemId,String extension){return staged(itemId,"",extension);}
    public Path staged(UUID itemId,String variant,String extension){
        String ext=extension==null?"":extension.replaceAll("[^A-Za-z0-9._-]","");
        String suffix=variant==null||variant.isBlank()?"":"-"+variant.replaceAll("[^A-Za-z0-9._-]","_");
        return root.resolve("output").resolve(itemId+suffix+ext);
    }
    public Path backup(UUID itemId,String fileName){String safe=fileName==null?"source":fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_");return root.resolve("backup").resolve(itemId+"-"+safe);}
    public Path root(){return root;}

    /** Removes staging artifacts for an item after its durable commit, keeping the crash manifest intact. */
    public void cleanupItem(UUID itemId) throws IOException {
        if (itemId == null) return;
        deleteMatching(root.resolve("output"), itemId.toString());
        deleteMatching(root.resolve("input"), itemId.toString());
        deleteMatching(root.resolve("backup"), itemId.toString());
    }

    public void cleanup() throws IOException { deleteTree(root); }

    private static void deleteMatching(Path directory, String prefix) throws IOException {
        if (directory == null || prefix == null || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        OrganizerPathSafety.rejectLinkLikeExistingComponents(directory, "Organizer workspace item cleanup");
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Organizer workspace item cleanup path is not a directory: " + directory);
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, prefix + "*")) {
            for (Path path : stream) {
                if (Files.isSymbolicLink(path)) { Files.deleteIfExists(path); continue; }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) deleteTree(path);
                else Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Deliberately does not remove the workspace. A process or application failure must leave recovery evidence behind.
     */
    @Override public void close() { }

    public static void deleteTree(Path root) throws IOException {
        if(root==null||!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(root)){
            // The link itself is inside our workspace namespace; removing it never traverses the target.
            Files.deleteIfExists(root);
            return;
        }
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)){
            throw new IOException("Organizer workspace cleanup root was replaced by a non-directory: " + root);
        }
        OrganizerPathSafety.rejectLinkLikeExistingComponents(root,"Organizer workspace cleanup");
        Files.walkFileTree(root,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path f,BasicFileAttributes a)throws IOException{Files.deleteIfExists(f);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path d,IOException e)throws IOException{if(e!=null)throw e;Files.deleteIfExists(d);return FileVisitResult.CONTINUE;}
        });
    }
}
