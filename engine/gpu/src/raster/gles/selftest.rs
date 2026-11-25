//! the graphics self-test: what a phone has to give the renderer, tried
//! without a game. The launcher starts the game's activity with the
//! "selftest" extra and this runs in its place: a GLES context on the
//! activity's own window, the engine's own fragment shader through the
//! driver's compiler, and a triangle drawn into the window and read back.

use std::ffi::c_void;

use glow::HasContext;

use super::display::Egl;
use super::programs::link;
use super::sources::fragment_source;
use super::FRAGMENT_CONSTANTS;

/// the triangle that covers the window, in the dialect the engine's own
/// programs are written in.
const TRIANGLE_VERTEX: &str = "#version 300 es
precision highp float;
const vec2 corners[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() { gl_Position = vec4(corners[gl_VertexID], 0.0, 1.0); }
";

const TRIANGLE_FRAGMENT: &str = "#version 300 es
precision highp float;
out vec4 color;
void main() { color = vec4(1.0, 0.5, 0.25, 1.0); }
";

/// what the draw should leave in the middle of the window, a byte a channel.
const EXPECTED: [u8; 4] = [255, 128, 64, 255];

/// runs the self-test, into the window when there is one and into an
/// offscreen buffer of 16x16 when the activity has none, as it has with the
/// screen off: its report, or what went wrong.
pub fn run(window: Option<*mut c_void>) -> Result<String, String> {
    let (_egl, gl, (width, height), into) = match window {
        Some(window) => {
            let (egl, gl, size) = Egl::new_window(window)?;
            (egl, gl, size, "the activity's window")
        }
        None => {
            let (egl, gl) = Egl::new()?;
            (egl, gl, (16, 16), "an offscreen buffer, the activity having no window")
        }
    };
    let mut report = String::new();
    // SAFETY: the context is current on this thread, made for the window just
    // above, and nothing else in the app is drawing
    unsafe {
        for (what, name) in [("renderer", glow::RENDERER), ("version", glow::VERSION), ("GLSL", glow::SHADING_LANGUAGE_VERSION)] {
            report += &format!("{what}: {}\n", gl.get_parameter_string(name));
        }
        report += &format!("drawing into {into}, {width}x{height}\n");
        // the engine's own fragment shaders, which the driver compiles: the
        // one part of the renderer a phone can refuse outright
        let tev = fragment_source(&[0u32; FRAGMENT_CONSTANTS], false, &[]);
        match compile(&gl, glow::FRAGMENT_SHADER, &tev) {
            Ok(()) => report += "the engine's fragment shader: compiles\n",
            Err(log) => return Err(format!("{report}the engine's fragment shader does not compile:\n{log}")),
        }
        // and a draw, read back the way the engine's pictures are
        let program = link(&gl, TRIANGLE_VERTEX, TRIANGLE_FRAGMENT)?;
        let vertex_array = gl.create_vertex_array().map_err(|error| error.to_string())?;
        gl.bind_vertex_array(Some(vertex_array));
        gl.use_program(Some(program));
        gl.clear_color(0.0, 0.0, 0.0, 1.0);
        gl.clear(glow::COLOR_BUFFER_BIT);
        gl.draw_arrays(glow::TRIANGLES, 0, 3);
        gl.finish();
        let mut pixel = [0u8; 4];
        gl.read_pixels(width / 2, height / 2, 1, 1, glow::RGBA, glow::UNSIGNED_BYTE, glow::PixelPackData::Slice(Some(&mut pixel[..])));
        let close = pixel.iter().zip(EXPECTED.iter()).all(|(drawn, wanted)| drawn.abs_diff(*wanted) <= 2);
        report += &format!("the triangle at the picture's middle: {pixel:?}\n");
        if !close {
            return Err(format!("{report}the draw did not put the triangle's colour in the window"));
        }
        gl.delete_program(program);
        gl.delete_vertex_array(vertex_array);
    }
    Ok(report)
}

/// compiles one stage of a program, its log when the driver refuses it.
///
/// # Safety
/// the context has to be current on this thread
unsafe fn compile(gl: &glow::Context, stage: u32, source: &str) -> Result<(), String> {
    let shader = gl.create_shader(stage).map_err(|error| error.to_string())?;
    gl.shader_source(shader, source);
    gl.compile_shader(shader);
    let compiled = gl.get_shader_compile_status(shader);
    let log = gl.get_shader_info_log(shader);
    gl.delete_shader(shader);
    if compiled {
        Ok(())
    } else {
        Err(log)
    }
}
