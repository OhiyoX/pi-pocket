# Publication model

Pocket Pi is developed in a private upstream repository. This public repository is a **sanitized snapshot** of a reviewed state, not a mirror of the private history.

## What is published

- A single, reviewed tree of the app, bridge, public docs and demo assets.
- A synthetic demo site with screenshots captured from the debug build against the mock bridge.
- Public issues and pull requests for community feedback.

## What is not published

- Private Git history, branches, tags or pull-request refs.
- Internal handoff notes, audits, incident records, personal agent profiles, deployment scripts and private screenshots.
- Real hostnames, private IP addresses, usernames, tokens, SSH keys or customer data.

## Synchronization

Publication is one-way:

1. A change is reviewed and merged in the private upstream.
2. The public snapshot is generated from the merged tree, with internal files removed and private identifiers replaced.
3. The sanitized tree is scanned, committed as a new public commit and pushed to GitHub.

Public commits and public CI results describe the public snapshot only. They do not prove the state of the private upstream, its tags or any deployment.

## Reporting a leak

If you believe a public file still contains a secret or personal identifier, use GitHub private vulnerability reporting (see [SECURITY.md](SECURITY.md)) instead of opening a public issue. We will remove or rotate the affected content.
