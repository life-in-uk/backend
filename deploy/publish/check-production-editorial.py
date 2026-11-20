"""Run as a production operator from /opt/lifeinuk. Output booleans only."""
import json
import re
import subprocess
import sys


def capture(*args):
    return subprocess.check_output(args, stderr=subprocess.DEVNULL, text=True)


def normalize(key):
    return re.sub(r"[^a-z0-9]", "", key.lower())


def check():
    container = capture("docker", "compose", "ps", "-q", "app").strip()
    active = json.loads(capture("docker", "inspect", container))[0]["Config"]
    planned = json.loads(capture("docker", "compose", "config", "--format", "json"))["services"]["app"]
    runtime = dict(item.split("=", 1) for item in active["Env"])
    effective = tuple({normalize(key): value for key, value in env.items()}
                      for env in (runtime, planned["environment"]))
    config_keys = ("springapplicationjson", "springconfiglocation", "springconfigadditionallocation",
                   "springconfigimport", "springconfigname")
    jvm_keys = ("javatooloptions", "jdkjavaoptions", "javaoptions")
    flags = str(active.get("Cmd") or "") + str(planned.get("command") or "")
    override = any(any(env.get(key) for key in config_keys) for env in effective)
    options = " ".join(str(env.get(key) or "") for env in effective for key in jvm_keys)
    override |= bool(re.search(r"(spring[._-]|editorial[._-])", options, re.I))
    override |= bool(re.search(r"(spring[._-](config|application)|editorial[._-])", flags, re.I))
    controls = {
        "mockReviewTokenUnset": "lifeinukeditorialmockreviewtoken",
        "localReviewTokenUnset": "lifeinukeditoriallocalreviewtoken",
        "bearerDraftTokenUnset": "lifeinukeditorialdrafttoken",
        "bearerApprovalTokenUnset": "lifeinukeditorialapprovaltoken",
    }
    result = {label: not override and all(key not in env for env in effective)
              for label, key in controls.items()}
    result["alternateEditorialConfigAbsent"] = not override
    if sys.argv[1:] == ["--after-cleanup"]:
        temporary = ("editorialownerdraftpassword", "editorialownerapprovalpassword",
                     "lifeinukeditorialreviewtoken", "lifeinukeditorialapprovalactor",
                     "editorialownerorigins", "editorialsessioncookiesecure")
        result["temporaryEditorialOverridesAbsent"] = not override and all(
            key not in env and "lifeinuk" + key not in env for env in effective for key in temporary)
    return result


if __name__ == "__main__":
    try:
        result = check()
    except (subprocess.SubprocessError, OSError, ValueError, KeyError, TypeError, IndexError):
        print('{"checkCompleted":false}')
        raise SystemExit(1)
    print(json.dumps(result, sort_keys=True))
    raise SystemExit(0 if all(result.values()) else 1)
