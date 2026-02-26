// argon2-stub.js - JS stub replacing argon2 native module for Termux
// The native argon2 module requires glibc and cannot run on Termux (Bionic libc).
// Since code-server is started with --auth none, argon2 is never actually called.
// This stub satisfies the require() without loading native code.

"use strict";

module.exports.hash = async function hash() {
    throw new Error("argon2 native module is not available on Termux. Use --auth none.");
};

module.exports.verify = async function verify() {
    throw new Error("argon2 native module is not available on Termux. Use --auth none.");
};

module.exports.needsRehash = function needsRehash() {
    return false;
};

// Argon2 type constants (for compatibility)
module.exports.argon2d = 0;
module.exports.argon2i = 1;
module.exports.argon2id = 2;
