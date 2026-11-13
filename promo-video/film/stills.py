"""Capture film stills at the supplied seconds using the shared Ego driver."""
import argparse
from driver import ROOT, browser_options, browser_kwargs, fresh_run, run_browser


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('times', nargs='+', type=float)
    browser_options(parser)
    args = parser.parse_args()
    out = fresh_run(args.output_dir or ROOT / 'scratch')
    run_browser(args.times, out, **browser_kwargs(args))
    print(out)


if __name__ == '__main__':
    main()
