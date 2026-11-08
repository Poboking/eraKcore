fn main() -> std::process::ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    std::process::exit(erbcc_rs::cli::run(&args))
}
