# Code Review
Description: Review a file or the changed files for bugs, security issues and style, then list findings with file and line.

Read the file (or the changed files) first with the read and glob tools, so you are reviewing the real code, not guessing from memory.

Check, in this order:
1. Bugs — wrong logic, off-by-one errors, nulls or missing values, unhandled errors, code that cannot run.
2. Security — secrets or API keys in the code, user input used without checking, unsafe file or network access.
3. Style — names that mislead, duplicated code, functions that do too much, dead code.

List every finding as: file and line, how serious it is (must fix / should fix / nice to have), what is wrong, and what to change. Group findings by file. Do not invent problems in code you have not read.

Do not change any file yourself — reviewing only. If the user asked for fixes as well, say so in one line at the end instead of fixing silently.

End with a short verdict: ready, needs fixes (say how many must-fix items), or needs a rewrite.
