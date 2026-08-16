//! Minimal stand-in for Zed's `gpui` crate, written for Conquest Code.
//!
//! The vendored Tier-0 crates (`rope`, `text`, `sum_tree`) only use gpui in
//! their tests, and only in two shapes:
//!
//! ```ignore
//! #[gpui::test]
//! fn plain_test() { ... }
//!
//! #[gpui::test(iterations = 100)]
//! fn randomized_test(mut rng: StdRng) { ... }
//! ```
//!
//! This crate re-implements just that attribute so those tests keep running
//! unchanged. Semantics mirror gpui's test runner for the no-executor case:
//! run `iterations` times with seeds `0..iterations`, overridable via the
//! `SEED` and `ITERATIONS` environment variables; on panic the failing seed
//! has been printed. The generated code refers to the `rand` crate, which all
//! consumers already have as a dev-dependency.

use proc_macro::TokenStream;
use quote::{format_ident, quote};
use syn::{Expr, ItemFn, Lit, Meta, parse_macro_input, punctuated::Punctuated, token::Comma};

#[proc_macro_attribute]
pub fn test(attr: TokenStream, item: TokenStream) -> TokenStream {
    let mut iterations: u64 = 1;

    let args = parse_macro_input!(attr with Punctuated::<Meta, Comma>::parse_terminated);
    for meta in args {
        match &meta {
            Meta::NameValue(nv) if nv.path.is_ident("iterations") => {
                if let Expr::Lit(lit) = &nv.value
                    && let Lit::Int(int) = &lit.lit
                    && let Ok(value) = int.base10_parse::<u64>()
                {
                    iterations = value;
                    continue;
                }
                return compile_error("`iterations` must be an integer literal");
            }
            // gpui also accepts `retries`, `on_failure`, `seeds(...)`; none of
            // the vendored crates use them.
            _ => return compile_error("unsupported gpui::test argument"),
        }
    }

    let inner_fn = parse_macro_input!(item as ItemFn);
    let inner_name = &inner_fn.sig.ident;
    let outer_name = format_ident!("{}", inner_name);
    let takes_rng = !inner_fn.sig.inputs.is_empty();

    if inner_fn.sig.inputs.len() > 1 {
        return compile_error("gpui test shim only supports fn() or fn(rng: StdRng) signatures");
    }

    let call = if takes_rng {
        quote! {
            let rng = <rand::rngs::StdRng as rand::SeedableRng>::seed_from_u64(seed);
            #inner_name(rng);
        }
    } else {
        quote! { #inner_name(); }
    };

    let expanded = quote! {
        #[test]
        fn #outer_name() {
            #inner_fn

            let iterations: u64 = std::env::var("ITERATIONS")
                .ok()
                .and_then(|value| value.parse().ok())
                .unwrap_or(#iterations);
            let starting_seed: u64 = std::env::var("SEED")
                .ok()
                .and_then(|value| value.parse().ok())
                .unwrap_or(0);

            for seed in starting_seed..starting_seed.saturating_add(iterations) {
                if iterations > 1 {
                    eprintln!("seed = {seed}");
                }
                #call
            }
        }
    };
    expanded.into()
}

fn compile_error(message: &str) -> TokenStream {
    let expanded = quote! { compile_error!(#message); };
    expanded.into()
}
