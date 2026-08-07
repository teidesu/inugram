//! The one http(s) url screen, shared by `inu.openUrl` and `fetch`.
//!
//! Both hand a url to something that re-parses it - the system's `ACTION_VIEW`, or java's `URI` -
//! so what matters is that the string cannot mean one host to a reader and another to the parser
//! that acts on it. Whitespace and control characters are refused rather than percent-encoded for
//! that reason, and userinfo (`https://telegram.org@evil.com/`) outright, nothing here having a use
//! for it. Browsers fold a backslash to a slash before the authority ends, so `https://evil.com\@ok.com`
//! is the same trick spelled differently.
//!
//! A scheme allowlist is the only check that can tell a *page* from an *action* before something
//! else has acted on the string: `tg:` is the app's own deeplink surface, `intent:` names an
//! activity and its extras, `file:`/`content:` name the storage the sandbox exists to gate.

/// The host, lowercased, with a trailing dot and any ipv6 brackets stripped - which is the form a
/// grant's domain match is against, `a.` and `a` being one name to dns and two strings to it.
pub fn parse_http_url(api: &str, url: &str) -> Result<String, String> {
    if url.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return Err(format!("{api}: a url may not contain whitespace or control characters"));
    }
    let Some((scheme, rest)) = url.split_once("://") else {
        return Err(format!("{api}: the url has no scheme"));
    };
    let scheme = scheme.to_ascii_lowercase();
    if scheme != "http" && scheme != "https" {
        return Err(format!("{api}: '{scheme}' is not a scheme this api speaks; http and https only"));
    }
    let authority = rest.split(['/', '?', '#']).next().unwrap_or_default();
    if authority.contains('@') {
        return Err(format!("{api}: a url with userinfo in it is refused"));
    }
    if authority.contains('\\') {
        return Err(format!("{api}: a url with a backslash in its authority is refused"));
    }
    let host = match authority.strip_prefix('[') {
        Some(rest) => match rest.split_once(']') {
            Some((inside, _)) => inside,
            None => return Err(format!("{api}: the url has an unterminated ipv6 literal")),
        },
        None => authority.split(':').next().unwrap_or_default(),
    };
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() {
        return Err(format!("{api}: the url has no host"));
    }
    Ok(host)
}
