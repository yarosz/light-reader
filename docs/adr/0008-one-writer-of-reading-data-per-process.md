# One writer of reading data per process

A files directory's reading data has exactly one owner in the process: `ShelfOwner`, which `ShelfOwner.of`
returns for that directory and which lives as long as the process. It holds the reading data, its saver,
which Books' files exist, and the foreground downloads. The Shelf's and the reading view's view models are
views onto it; none of them owns reading data.

The platform fact behind this: **LightOS relaunches a Tool's activity in the same process when it is
reopened, and never clears the previous screens' view models** (`onCleared` doesn't run). A reopened Tool
therefore has an old Shelf view model and a new one alive at once. If each held its own copy of the reading
data, the old copy would go stale and its next flush would write the stale state back. A save merges the
writer's data into the file, and takes each Book's file and whether it is on the Shelf from the writer,
because the writer is the one that owns the files. So the stale writer brings back a Book the reader
removed, and drops a download that landed after its copy was taken. (The merge keeps the newer Place by
its time, so a stale writer can't roll a Place back; the files are what it gets wrong.) The test
`two Shelves in one process share one owner, so a removal in one isn't undone by the other's flush` pins
this.

This is surprising without the platform fact: on stock Android a view model is the natural owner, and a
relaunched activity's old one is cleared.

## Considered Options

- One saver per view model: the Android default, and exactly the two-writer case above once LightOS keeps
  the old view model alive.
- File locking alone: `ReadingStore` does take a per-directory save lock, so two saves never interleave on
  disk. It doesn't stop two in-memory copies diverging: each save is whole and in order, and the stale one
  still writes its stale state.
- A background service that owns the data: ruled out by "no background service in v1", and it would outlive
  the Tool for no reader-visible gain.
