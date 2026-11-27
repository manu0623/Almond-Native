//! writing a list whole or not at all.
//!
//! The lists a run reads (a title's shaders, the vertex list, the precache
//! list) are written by a build that a person can kill, a battery can cut or
//! a phone can run out of room on. A file half written still starts with its
//! header and reads as a whole list with lines missing, which is worse than
//! no list: the shaders those lines name are then built in game. So each one
//! goes to a file beside it and takes its place once every byte is down.

/// writes `bytes` to `path` through `<name>.writing` beside it, which then
/// takes its name: a build killed mid-write leaves the list that was there.
pub fn write(path: &std::path::Path, bytes: &[u8]) -> std::io::Result<()> {
    use std::io::Write;
    if let Some(parent) = path.parent().filter(|parent| !parent.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)?;
    }
    let beside = path.with_extension("writing");
    let mut file = std::fs::File::create(&beside)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    std::fs::rename(&beside, path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_list_takes_its_place_whole() {
        let dir = std::env::temp_dir().join(format!("whole-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let path = dir.join("precache.txt");
        write(&path, b"first\n").unwrap();
        write(&path, b"second\n").unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "second\n");
        assert!(!path.with_extension("writing").exists(), "the file beside it is left behind");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_write_that_fails_leaves_the_list_that_was_there() {
        let dir = std::env::temp_dir().join(format!("whole-kept-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let path = dir.join("precache.txt");
        write(&path, b"old\n").unwrap();
        // where the new list would go, nothing can be written, as on a card
        // with no room left
        std::fs::create_dir_all(path.with_extension("writing")).unwrap();
        assert!(write(&path, b"new\n").is_err());
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "old\n");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
