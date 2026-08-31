# Documentation

The sensor work moved out. Everything about the GH3011 - its registers, the ioctls, what the
vendor's daemon does with them, and every measurement the vitals came from - now lives in the
`gh3011-vitals` repository, checked out here as the `vitals` submodule:

    vitals/docs/gh3011.md    the chip and the vendor's code
    vitals/docs/vitals.md    the measurements, and what does not work
    vitals/docs/data/        the captures and cuff references behind them

It moved because the launcher never used any of it directly. The only thing crossing between them
is one line over an abstract socket, which `OwnVitals` speaks and `vitalsd` answers, so the two can
be worked on and versioned separately. `git clone --recursive`, or `git submodule update --init`
in an existing checkout.
