# wavelength

FIXME: my new application.


## Running

Spin up a repl using `clj -M:repl/run` and that _should_ start the server on 8080

You probably need a cljs build step _somewhere_ but using `clj -M:repl/fig` for now to run fig-wheel

## TODO

### NEXT
* [X] Psychic Phase
  * [X] Pick a psychic
    * [X] disconnects (to fix the disconnects bug). Go back to pick
  * [X] let them pick a card
  * [X] let them add a clue
* [X] Guessing Phase
* [_] L/R Phase
* [_] Figure out scores and move on to the next round
* [_] Figure out if someone has won


### Things to keep an eye on

* I ended up letting the lobby start the game _but_ you pass the game to the lobby.
  * There was a bit too much going on with the lobby knowing to send the lobby code out to players for me to lift
    that information out
* spectators should be able to join at any point really...
  * So does that mean we need to break joining and the lobbies atom out of the lobbie ns?
  * TODO: Let's ignore this for now and handle it later once things re working

### Bugs

* No inputs to stately cause hot-loop if there is a default that recurs
  * I mean what does it mean have no inputs... that seems an error
  * Actually... is it that the real problem or it that it now null since they are closed
    * probably need to figure out leaving asap and make it reusable 
* Deck is an infinite sequence that which is fine until println tries to walk the entire thing
  * Will a logging framework make the same mistake? How can you get around it? 
* So it turns out that pick wavelength after you've been a psychic bugs out and thinks you are the
  psychic each time even if not in the same team
  * Maybe there should be clean-up functions like `on-exit` or something?
  * This one might triggered by going back to the lobby and then going back to being on an active team
    * so could be sorted by a better on-entry cleaning things up better... maybe?

### LATER
* Allow spectators to join during the game
* Improve the looks... massively

...

## License

Copyright © 2022 Gavinesberger

Distributed under the Eclipse Public License version 1.0.
