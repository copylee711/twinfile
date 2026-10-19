// Runs commands in Shizuku's shell-uid process for TwinFile.
package app.twinfile.priv;

interface IPrivService {
    // Shizuku calls this transaction id to stop the service.
    void destroy() = 16777114;

    // Starts argv with the given pipe ends (any may be null) and returns a handle for waitFor.
    int start(in String[] argv, in ParcelFileDescriptor stdin, in ParcelFileDescriptor stdout, in ParcelFileDescriptor stderr) = 1;

    // Blocks until the process exits; returns its exit code.
    int waitFor(int handle) = 2;
}
