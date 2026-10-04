// Fixture for the Blackbox conformance suite (editors/conformance/test).
// The tests refer to these line numbers; keep them stable.
package main

import "fmt"

func add(a, b int) int {
	return a + b
}

func main() {
	total := 0
	for i := 1; i <= 2; i++ {
		total = add(total, i) // line 14: breakpoint
		fmt.Println("total", total) // line 15: logpoint
	}
	fmt.Println("done", total)
}
