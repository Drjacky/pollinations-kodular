#!/usr/bin/env python3
"""Builds sample/PollinationsDemo.aia: a ready Kodular (and App Inventor) project that uses the extension.

Run after `fast build`. The .aix contents are bundled in the project (assets/external_comps)
the same way App Inventor exports projects that use extensions, so importing the .aia is enough.
"""
import json
import os
import sys
import zipfile
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "io.github.drjacky.pollinations"
EXT_DIR = os.path.join(ROOT, ".fast", PKG)
OUT = os.path.join(ROOT, "sample", "PollinationsDemo.aia")
PROJECT = "PollinationsDemo"
USER = "drjacky"
YA_VERSION = "224"
BLOCKS_VERSION = "36"

_uuid = [1000]


def comp(type_, name, version, **props):
    _uuid[0] += 1
    c = {"$Name": name, "$Type": type_, "$Version": str(version), "Uuid": str(_uuid[0])}
    children = props.pop("children", None)
    for k, v in props.items():
        c[k] = v
    if children is not None:
        c["$Components"] = children
    return c


def form():
    return {
        "authURL": ["creator.kodular.io"],
        "YaVersion": YA_VERSION,
        "Source": "Form",
        "Properties": {
            "$Name": "Screen1", "$Type": "Form", "$Version": "30", "Uuid": "0",
            "AppName": PROJECT, "Title": "Pollinations demo", "Scrollable": "True",
            "Sizing": "Responsive", "Theme": "DeviceDefault", "AccentColor": "&HFF7C3AED",
            "PrimaryColor": "&HFF111827", "PrimaryColorDark": "&HFF000000",
            "$Components": [
                comp("Label", "LabelStatus", 5, Text="Not signed in", Width="-2",
                     FontSize="16"),
                comp("Button", "ButtonSignIn", 7, Text="Sign in with my Pollen", Width="-2"),
                comp("TextBox", "TextBoxPrompt", 6, Width="-2", MultiLine="True",
                     Hint="Ask something, or describe a picture",
                     Text="A friendly robot bee reading a book"),
                comp("HorizontalArrangement", "RowText", 4, Width="-2", children=[
                    comp("Button", "ButtonAsk", 7, Text="Ask", Width="-2"),
                    comp("Button", "ButtonImage", 7, Text="Make image", Width="-2"),
                    comp("Button", "ButtonSpeak", 7, Text="Speak", Width="-2"),
                ]),
                comp("HorizontalArrangement", "RowMedia", 4, Width="-2", children=[
                    comp("ImagePicker", "ImagePickerAsk", 5, Text="Ask about a photo",
                         Width="-2"),
                    comp("Button", "ButtonRecord", 7, Text="Record", Width="-2"),
                ]),
                comp("HorizontalArrangement", "RowMore", 4, Width="-2", children=[
                    comp("Button", "ButtonModels", 7, Text="Text model", Width="-2"),
                    comp("Button", "ButtonBalance", 7, Text="My Pollen", Width="-2"),
                    comp("Button", "ButtonSignOut", 7, Text="Sign out", Width="-2"),
                ]),
                comp("Label", "LabelAnswer", 5, Text="", Width="-2", HasMargins="True"),
                comp("Image", "ImageResult", 6, Width="-2", ScalePictureToFit="True"),
                comp("ListPicker", "ListPickerModels", 9, Visible="False",
                     Title="Pick a text model", ShowFilterBar="True"),
                comp("Player", "Player1", 6),
                comp("Notifier", "Notifier1", 6),
                comp(PKG + ".Pollinations", "Pollinations1", 1,
                     SystemPrompt="Answer in two short sentences."),
            ],
        },
    }


# ------------------------------------------------------------------------------------------
# Tiny Blockly XML builder


class B:
    def __init__(self):
        self.y = 20

    def place(self, xml):
        out = xml.replace("<block ", '<block x="20" y="%d" ' % self.y, 1)
        self.y += 160
        return out


def text(s):
    return '<block type="text"><field name="TEXT">%s</field></block>' % escape(s)


def boolean(v):
    return ('<block type="logic_boolean"><field name="BOOL">%s</field></block>'
            % ("TRUE" if v else "FALSE"))


def param(name):
    return ('<block type="lexical_variable_get"><mutation><eventparam name="%s"/></mutation>'
            '<field name="VAR">%s</field></block>' % (name, name))


def get(ctype, inst, prop):
    return ('<block type="component_set_get"><mutation component_type="%s" set_or_get="get" '
            'property_name="%s" is_generic="false" instance_name="%s"></mutation>'
            '<field name="COMPONENT_SELECTOR">%s</field><field name="PROP">%s</field></block>'
            % (ctype, prop, inst, inst, prop))


def set_(ctype, inst, prop, value, nxt=""):
    return ('<block type="component_set_get"><mutation component_type="%s" set_or_get="set" '
            'property_name="%s" is_generic="false" instance_name="%s"></mutation>'
            '<field name="COMPONENT_SELECTOR">%s</field><field name="PROP">%s</field>'
            '<value name="VALUE">%s</value>%s</block>'
            % (ctype, prop, inst, inst, prop, value, nxt_wrap(nxt)))


def call(ctype, inst, method, args=(), nxt=""):
    vals = "".join('<value name="ARG%d">%s</value>' % (i, a) for i, a in enumerate(args))
    return ('<block type="component_method"><mutation component_type="%s" method_name="%s" '
            'is_generic="false" instance_name="%s"></mutation>'
            '<field name="COMPONENT_SELECTOR">%s</field>%s%s</block>'
            % (ctype, method, inst, inst, vals, nxt_wrap(nxt)))


def join(*parts):
    vals = "".join('<value name="ADD%d">%s</value>' % (i, p) for i, p in enumerate(parts))
    return '<block type="text_join"><mutation items="%d"></mutation>%s</block>' % (
        len(parts), vals)


def if_else(cond, then, otherwise, nxt=""):
    return ('<block type="controls_if"><mutation else="1"></mutation>'
            '<value name="IF0">%s</value><statement name="DO0">%s</statement>'
            '<statement name="ELSE">%s</statement>%s</block>'
            % (cond, then, otherwise, nxt_wrap(nxt)))


def nxt_wrap(nxt):
    return "<next>%s</next>" % nxt if nxt else ""


def chain(*stmts):
    """Links statement blocks with <next> (each stmt is built with nxt=...)."""
    out = ""
    for s in reversed(stmts):
        out = s(out)
    return out


def event(ctype, inst, name, body):
    return ('<block type="component_event"><mutation component_type="%s" is_generic="false" '
            'instance_name="%s" event_name="%s"></mutation>'
            '<field name="COMPONENT_SELECTOR">%s</field><statement name="DO">%s</statement>'
            '</block>' % (ctype, inst, name, inst, body))


P = ("Pollinations", "Pollinations1")


def blocks():
    b = B()
    prompt = get("TextBox", "TextBoxPrompt", "Text")
    status = lambda v: (lambda n: set_("Label", "LabelStatus", "Text", v, n))
    answer = lambda v: (lambda n: set_("Label", "LabelAnswer", "Text", v, n))
    ev = []
    ev.append(event("Form", "Screen1", "Initialize", chain(
        lambda n: if_else(get(*P, "IsSignedIn"),
                          set_("Label", "LabelStatus", "Text",
                               join(text("Signed in as "), get(*P, "UserName"))),
                          set_("Label", "LabelStatus", "Text",
                               text("Tap Sign in. You pay with your own Pollen.")), n))))
    ev.append(event("Button", "ButtonSignIn", "Click", chain(
        status(text("Opening the sign-in page...")),
        lambda n: call(*P, "SignIn", (), n))))
    ev.append(event(*P, "SignInCode", chain(
        status(join(text("Approve code "), param("userCode"),
                    text(" in the browser. Waiting...")))))
    )
    ev.append(event(*P, "SignedIn", chain(
        status(join(text("Signed in as "), param("userName"))))))
    ev.append(event("Button", "ButtonAsk", "Click", chain(
        answer(text("Thinking...")),
        lambda n: call(*P, "AskText", (prompt,), n))))
    ev.append(event(*P, "GotText", chain(answer(param("text")))))
    ev.append(event("Button", "ButtonImage", "Click", chain(
        answer(text("Drawing...")),
        lambda n: call(*P, "GenerateImage", (prompt,), n))))
    ev.append(event(*P, "GotImage", chain(
        lambda n: set_("Image", "ImageResult", "Picture", param("image"), n),
        answer(text("")))))
    ev.append(event("Button", "ButtonSpeak", "Click", chain(
        lambda n: call(*P, "Speak", (prompt, text("")), n))))
    ev.append(event(*P, "GotSpeech", chain(
        lambda n: set_("Player", "Player1", "Source", param("audioFile"), n),
        lambda n: call("Player", "Player1", "Start", (), n))))
    ev.append(event("ImagePicker", "ImagePickerAsk", "AfterPicking", chain(
        lambda n: set_("Image", "ImageResult", "Picture",
                       get("ImagePicker", "ImagePickerAsk", "Selection"), n),
        answer(text("Looking at the photo...")),
        lambda n: call(*P, "AskAboutImage",
                       (prompt, get("ImagePicker", "ImagePickerAsk", "Selection")), n))))
    ev.append(event("Button", "ButtonRecord", "Click", chain(
        lambda n: if_else(
            get(*P, "IsRecording"),
            call(*P, "StopRecording", (boolean(True),),
                 set_("Button", "ButtonRecord", "Text", text("Record"),
                      set_("Label", "LabelAnswer", "Text", text("Listening back...")))),
            call(*P, "StartRecording", (),
                 set_("Button", "ButtonRecord", "Text", text("Stop"))), n))))
    ev.append(event(*P, "GotTranscript", chain(
        lambda n: set_("TextBox", "TextBoxPrompt", "Text", param("text"), n),
        answer(text("Transcribed. Tap Ask to send it.")))))
    ev.append(event("Button", "ButtonModels", "Click", chain(
        lambda n: call(*P, "GetModels", (text("text"),), n))))
    ev.append(event(*P, "GotModels", chain(
        lambda n: set_("ListPicker", "ListPickerModels", "Elements", param("models"), n),
        lambda n: call("ListPicker", "ListPickerModels", "Open", (), n))))
    ev.append(event("ListPicker", "ListPickerModels", "AfterPicking", chain(
        lambda n: set_("Pollinations", "Pollinations1", "TextModel",
                       get("ListPicker", "ListPickerModels", "Selection"), n),
        lambda n: set_("Button", "ButtonModels", "Text",
                       get("ListPicker", "ListPickerModels", "Selection"), n))))
    ev.append(event("Button", "ButtonBalance", "Click", chain(
        lambda n: call(*P, "GetBalance", (), n))))
    ev.append(event(*P, "GotBalance", chain(
        answer(join(text("Pollen left: "), param("pollen"))))))
    ev.append(event("Button", "ButtonSignOut", "Click", chain(
        lambda n: call(*P, "SignOut", (), n),
        status(text("Signed out")))))
    ev.append(event(*P, "ErrorOccurred", chain(
        lambda n: call("Notifier", "Notifier1", "ShowAlert",
                       (join(param("action"), text(": "), param("message")),), n))))
    body = "".join(b.place(e) for e in ev)
    return ('<xml xmlns="https://developers.google.com/blockly/xml">%s'
            '<yacodeblocks ya-version="%s" language-version="%s"></yacodeblocks></xml>'
            % (body, YA_VERSION, BLOCKS_VERSION))


def main():
    if not os.path.isdir(EXT_DIR):
        sys.exit("Run `fast build` first (missing %s)" % EXT_DIR)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    src = "src/appinventor/ai_%s/%s/" % (USER, PROJECT)
    props = "\n".join([
        "main=appinventor.ai_%s.%s.Screen1" % (USER, PROJECT),
        "name=%s" % PROJECT,
        "assets=../assets",
        "source=../src",
        "build=../build",
        "versioncode=1",
        "versionname=1.0",
        "useslocation=False",
        "aname=%s" % PROJECT,
        "sizing=Responsive",
        "showlistsasjson=True",
        "tutorialurl=",
        "subsetjson=",
        "actionbar=True",
        "theme=DeviceDefault",
        "color.primary=&HFF111827",
        "color.primary.dark=&HFF000000",
        "color.accent=&HFF7C3AED",
        "defaultfilescope=App",
        "",
    ])
    scm = "#|\n$JSON\n" + json.dumps(form(), separators=(",", ":")) + "\n|#"
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("youngandroidproject/project.properties", props)
        z.writestr(src + "Screen1.scm", scm)
        z.writestr(src + "Screen1.bky", blocks())
        for dirpath, _, files in os.walk(EXT_DIR):
            for f in sorted(files):
                full = os.path.join(dirpath, f)
                rel = os.path.relpath(full, EXT_DIR).replace(os.sep, "/")
                z.write(full, "assets/external_comps/%s/%s" % (PKG, rel))
    print("wrote", os.path.relpath(OUT, ROOT))


if __name__ == "__main__":
    main()
