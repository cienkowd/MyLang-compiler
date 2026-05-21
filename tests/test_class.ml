class Counter {
    int value

    fun void set(int v) {
        value = v
    }

    fun int get() {
        return value
    }

    fun void increment() {
        value = value + 1
    }
}

Counter c
c.set(0)
c.increment()
c.increment()
c.increment()
int val = c.get()
print val

class Rectangle {
    int width
    int height

    fun int area() {
        return width * height
    }
}

Rectangle rect
rect.width = 6
rect.height = 7
int a = rect.area()
print a
