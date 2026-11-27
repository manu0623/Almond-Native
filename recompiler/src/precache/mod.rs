//! the shaders a title's models need, worked out from their materials as the
//! library sets the GPU up for them, NintendoWare's (CGFX) and the SDK's H3D
//! (BCH), for the engine to build before the game starts, with the lines of
//! titles' lists of what runs of the games built. what the title's own code
//! draws, its layouts, text and effects or a pass it sets up itself, is left
//! to be built as the game needs it, unless a list has it.

mod bch;
mod cgfx;
mod lists;

use std::collections::{BTreeMap, HashMap, HashSet};
use std::path::Path;

#[cfg(test)]
pub(crate) use lists::raw_key;
pub use lists::{learn, Files, TITLE_HEADER};

use crate::rom::Title;
use crate::shaders::{self, Shbin};

pub const HEADER: &str = "pocket-native precache 1";

const STAGES: [usize; 6] = [0xC0, 0xC8, 0xD0, 0xD8, 0xF0, 0xF8];
const UNIT_PARAMS: [usize; 3] = [0x83, 0x93, 0x9B];
/// the coordinates units 2 and 3 read, for configs 0120, 0110, 0111, 0112,
/// 0121 and 0122.
const COORDINATES: [u32; 6] = [0, 0x2000, 0x2100, 0x2200, 0x100, 0x200];
/// the bit that turns each lookup table off: reflection red, green and blue,
/// distribution 0 and 1 and fresnel.
const DISABLE: [u32; 6] = [20, 21, 22, 16, 17, 19];
/// the fog the list works out for a material that asks for it: off and every
/// mode the hardware takes, since the scene's fog, which the titles set from
/// their own code, is one the material's drawing may or may not have.
const FOG_MODES: [u32; 8] = [0, 1, 2, 3, 4, 5, 6, 7];
/// the modes it had before the others were worked out too: off, and 5, the
/// one the engine draws, the other one, 7, being gas.
const BASE_FOG_MODES: [u32; 2] = [0, 5];
const ETC1A4: u32 = 13;
/// at most this many, every line a material asks for itself first, then the
/// variants a pass may set, the most shared first: a variant must not crowd
/// out a line a model needs, which is the one built in game otherwise, a
/// first compile costing about a third of a second on a Mali-G31 and a tenth
/// on an Adreno, and a program the list leaves out drawn as a plain stand-in
/// until it is built, which a tree pops in late for. A bigger list is a
/// longer start, which is the better trade against hitches in play.
const MOST: usize = 8000;

/// a line of the list: how far up it stands, and whether it is what a
/// material asks for itself rather than a variant a pass may set, with depth
/// written, more lights or another fog mode than the material's own.
///
/// The rank is how many materials give the line, a title's own list standing
/// above every material's, so that the lines of the lists are kept first.
#[derive(Default)]
pub(super) struct Line {
    rank: usize,
    base: bool,
}

/// what a title's files hold.
#[derive(Default)]
struct Found {
    /// textures' formats by name.
    formats: HashMap<String, u32>,
    cgfx: cgfx::Models,
    bch: bch::Models,
}

/// writes precache.txt into dir, each line a vertex shader of the list
/// write_vertex_list made from found, file:entry, and a setup as the
/// engine's own list keeps them: the title's own list, the lines of the other
/// titles' lists whose shaders this one has too, and what its models need,
/// each with the variants a pass may set too. the lines are kept in rounds
/// over the vertex shaders they name, every material's own before any
/// variant and one line of every shader before a second of any: a shader the
/// list leaves out is every one of its programs built in game, where a first
/// compile costs a tenth of a second. how many lines, none for a title with
/// none of them, which writes nothing.
pub fn write(title: &Title, found: &[Shbin], dir: &Path, lists: &[&Path]) -> std::io::Result<usize> {
    let mut seen = Found::default();
    shaders::files(title, &mut |data, source| {
        if data.starts_with(b"CGFX") {
            cgfx::read(data, source, &mut seen);
        } else if data.starts_with(b"BCH\0") {
            bch::read(data, source, &mut seen);
        }
    });
    let named: HashSet<Vec<u8>> = title.code().map(|code| code.split(|&byte| byte == 0).map(<[u8]>::to_vec).collect()).unwrap_or_default();
    let mut lines = cgfx::lines(&seen, found, &named);
    bch::lines(&seen, found, &mut lines);
    lists::add(title, found, dir, lists, &mut lines)?;
    let lines: Vec<(String, Line)> = lines.into_iter().collect();
    if lines.is_empty() {
        return Ok(0);
    }
    let (kept, shaders) = kept_under(&lines, MOST);
    if lines.len() > MOST {
        println!("{} shaders worked out over {shaders} vertex shaders, the {MOST} kept: every shader's own first, then the most shared variants", lines.len());
    }
    // whole or not at all: a list half written reads as a whole one that is
    // missing lines, whose shaders would then be built in game
    let text = std::iter::once(HEADER).chain(kept.iter().copied()).collect::<Vec<_>>().join("\n") + "\n";
    crate::whole::write(&dir.join("precache.txt"), text.as_bytes())?;
    Ok(kept.len())
}

/// the lines to keep out of all those worked out, at most `most` of them,
/// and how many vertex shaders they name: a round of every shader's own
/// lines first, one from each, then a round of its variants the same way, so
/// that a shader with a few thousand variants cannot take another's own line
/// out of the list. Within a round the most shared go first, and lines given
/// alike go by the line itself, so that the same title writes the same list
/// every time.
pub(super) fn kept_under<'a>(lines: &'a [(String, Line)], most: usize) -> (Vec<&'a str>, usize) {
    // by the vertex shader each line names, its own kept apart from its
    // variants: a title's list leads with file:entry, a model's with the
    // shader's own number, both naming the same shader
    let mut groups: BTreeMap<&str, (Vec<(&str, usize)>, Vec<(&str, usize)>)> = BTreeMap::new();
    for (line, rank) in lines {
        let vertex = line.split([' ', ':']).next().unwrap_or_default();
        let group = groups.entry(vertex).or_default();
        let laid = if rank.base { &mut group.0 } else { &mut group.1 };
        laid.push((line.as_str(), rank.rank));
    }
    for (own, variants) in groups.values_mut() {
        for laid in [own, variants] {
            laid.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(b.0)));
            laid.reverse();
        }
    }
    // a round of every shader's own, then a round of every shader's variants,
    // until the lines run out or the budget is spent
    let shaders = groups.len();
    let mut kept: Vec<&str> = Vec::with_capacity(most.min(lines.len()));
    for own in [true, false] {
        loop {
            let mut took = false;
            for (their, variants) in groups.values_mut() {
                if kept.len() >= most {
                    break;
                }
                if let Some((line, _)) = (if own { their } else { variants }).pop() {
                    kept.push(line);
                    took = true;
                }
            }
            if !took || kept.len() >= most {
                break;
            }
        }
    }
    (kept, shaders)
}

/// the lighting registers for config 0 and 1, the tables' absolutes,
/// inputs and scales, a light's flags and how many lights.
fn light_regs(config0: u32, config1: u32, [absolute, inputs, scales]: [u32; 3], light: u32, count: u32) -> BTreeMap<usize, u32> {
    let slots = (0..count).fold(0, |slots, slot| slots | slot << (4 * slot));
    let mut regs = BTreeMap::from([(0x8F, 1), (0x1C2, count - 1), (0x1C3, config0), (0x1C4, config1), (0x1D0, absolute), (0x1D1, inputs), (0x1D2, scales), (0x1D9, slots)]);
    for slot in 0..8 {
        regs.insert(0x149 + 0x10 * slot, if slot < count as usize { light } else { 1 });
    }
    regs
}

/// a material's GPU commands, words long from at, into regs with their
/// byte masks.
fn commands(d: &[u8], at: usize, words: usize, regs: &mut BTreeMap<usize, u32>) -> Option<()> {
    let w = (0..words).map(|k| u32_at(d, at + 4 * k)).collect::<Option<Vec<u32>>>()?;
    let mut j = 0;
    while j + 1 < w.len() {
        let (param, header) = (w[j], w[j + 1]);
        let (register, mask, extra, sequential) = ((header & 0xFFFF) as usize, header >> 16 & 0xF, (header >> 20 & 0xFF) as usize, header >> 31 != 0);
        if header == 0 || register >= 0x300 || mask == 0 {
            break;
        }
        let bytes = (0..4).filter(|byte| mask >> byte & 1 != 0).fold(0u32, |bits, byte| bits | 0xFF << (8 * byte));
        for (k, value) in std::iter::once(param).chain(w[(j + 2).min(w.len())..(j + 2 + extra).min(w.len())].iter().copied()).enumerate() {
            let target = if sequential { register + k } else { register };
            let old = regs.get(&target).copied().unwrap_or(0);
            regs.insert(target, old & !bytes | value & bytes);
        }
        j += 2 + extra + (extra & 1);
    }
    Some(())
}

fn u16_at(d: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_le_bytes(d.get(at..at + 2)?.try_into().ok()?))
}

fn u32_at(d: &[u8], at: usize) -> Option<u32> {
    Some(u32::from_le_bytes(d.get(at..at + 4)?.try_into().ok()?))
}

fn cstr(d: &[u8], at: usize) -> Option<String> {
    let end = d.get(at..)?.iter().position(|&byte| byte == 0)?;
    Some(d[at..at + end].iter().map(|&byte| byte as char).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn commands_keep_what_their_byte_masks_leave() {
        let mut regs = BTreeMap::from([(0x107, 0x1F00)]);
        // 0x107's low byte alone, then 0x126 whole
        let words: Vec<u8> = [0x41u32, 0x0001_0107, 0x0300_0000, 0x000F_0126].iter().flat_map(|word| word.to_le_bytes()).collect();
        commands(&words, 0, 4, &mut regs).unwrap();
        assert_eq!(regs[&0x107], 0x1F41);
        assert_eq!(regs[&0x126], 0x0300_0000);
    }

    fn line(text: &str, base: bool, rank: usize) -> (String, Line) {
        (text.to_owned(), Line { rank, base })
    }

    #[test]
    fn a_shaders_own_line_is_kept_before_anothers_variants() {
        // one shader with thousands of variants beside one with a single own
        // line: the budget must not be spent on the variants alone
        let mut lines = vec![line("1 0", true, 1), line("0 own", false, 900)];
        for more in 0..5 {
            lines.push(line(&format!("0 v{more}"), false, 800 - more));
        }
        let (kept, shaders) = kept_under(&lines, 3);
        assert_eq!(shaders, 2);
        assert_eq!(kept, vec!["1 0", "0 own", "0 v0"]);
    }

    #[test]
    fn the_most_shared_go_first_and_alike_lines_by_their_own() {
        let lines = vec![line("0 b", true, 5), line("0 a", true, 5), line("0 c", true, 9)];
        let (kept, _) = kept_under(&lines, 10);
        assert_eq!(kept, vec!["0 c", "0 a", "0 b"]);
    }

    #[test]
    fn a_lists_line_and_a_models_line_name_the_same_shader() {
        // a title's list leads with file:entry, a model's with the shader's
        // own number: both name one shader, and are kept as its own
        let lines = vec![line("0 0", true, 1), line("0:1a 0", true, usize::MAX)];
        let (kept, shaders) = kept_under(&lines, 1);
        assert_eq!(shaders, 1);
        assert_eq!(kept, vec!["0:1a 0"]);
    }
}
