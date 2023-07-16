# Stately

Stately was a mini-attempt to try and make working with [Clojure `core.async`](https://github.com/clojure/core.async)
"friendlier".

Originally `Wavelength` used `core.async` directly with different functions representing each state using 
go-blocks to consume from channels, processing those messages then switching states by calling into another state 
function.

While this worked, state transitions made testing difficult. Also, the `core.async` boilerplate
felt quite distracting mixed in with the logic of the game. This led to the following desires:
* State functions should be pure
* Try to keep state functions as focused on game logic as possible...
* ... By moving async concerns to the edges or outside of game logic
* Try to be declarative if possible

You can see some simple usage in `src/lib/stately/example.clj` but 
`src/wavelength/game.clj` contains the "real-world" usage of this "library".

## Conclusions

The big question is: "Is `core.async` really for this kind of thing?"...

I'm on the fence about the answer. My old, inner-Erlang developer immediately felt like it was and jumped
in ready solve the problem the old `gen_server` way. Maybe that is why the first attempt didn't
really work out.

Lots of talks & articles later, I was sure this was a "co-ordination" problem and that `core.async`
was the right tool for the job. But maybe it just needed a little abstracting away? 
In the end, I think `stately` proved itself useful (especially being able to test some "tricky" bits).
However, it still felt like I wasn't doing it quite right.
(I'll admit `gen_fsm` was still in the back of my mind when drafting out `stately`)

As an example, Rich and Stu talked about using `core.async` for "conveyance" using it as little
"machines" connected together to make bigger pieces. I don't think this Wavelength port really did that.
But when I look at something like [Bruce Hauman's Dots Game](https://rigsomelight.com/2013/08/12/clojurescript-core-async-dots-game.html),
you can really see a "stream" of gestures joining with game piece positions to make a "stream" of selected pieces.
