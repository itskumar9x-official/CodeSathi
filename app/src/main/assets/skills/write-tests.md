# Write Tests
Description: Write small focused tests for a function or a file.

Read the function or file first with the read tool, so the tests match what the code really does.

For each behaviour, write one small test: a clear name saying what is checked, the smallest input that exercises it, and the expected result. Cover the happy path first, then edge cases — empty input, missing values, bad input, and the boundary (zero, one, many).

Keep every test short and independent: no test should depend on another test running first. Do not change the production code to make a test pass, unless the user asked for that. If a behaviour cannot be tested as written (for example it reads a file or the network directly), say so in one line instead of writing a fake test.

Put the tests in the project's test file for that code, or propose the new test file with the write tool and wait for approval like any other file change.
