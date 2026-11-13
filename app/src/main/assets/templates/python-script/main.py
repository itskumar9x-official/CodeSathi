"""A small Python starter.

Run it with:  python3 main.py
Then make it yours: change greet() and add your own functions below.
"""


def greet(name):
    """Return a friendly greeting for one name."""
    return f"Hello, {name}!"


def add(a, b):
    """Add two numbers — a tiny example to grow from."""
    return a + b


def main():
    name = input("Your name: ").strip() or "friend"
    print(greet(name))
    print("2 + 3 =", add(2, 3))
    print("Done — now edit main.py and build something.")


if __name__ == "__main__":
    main()
